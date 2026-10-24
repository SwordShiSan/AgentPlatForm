package com.agentplatform.infrastructure.storage;

import com.agentplatform.domain.files.AssetStore;
import com.agentplatform.domain.files.FileFailure;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

/** Managed SDK client; exactly one bounded multipart transfer, never a full-file buffer. */
public final class S3AssetStore implements AssetStore {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(S3AssetStore.class);
    public static final long MAX_BYTES = 100L * 1024 * 1024;
    private static final int PART_BYTES = 8 * 1024 * 1024;
    private final S3Client client;
    private final String bucket;
    private final Semaphore transfer = new Semaphore(1);

    public S3AssetStore(S3Client client, String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    @Override public Identity put(String key, long bytes, String expectedSha256, InputStream content) {
        if (bytes < 0 || bytes > MAX_BYTES || expectedSha256 == null || !expectedSha256.matches("[a-f0-9]{64}"))
            throw new FileFailure(400, "INVALID_FILE_CONTENT_IDENTITY");
        if (!transfer.tryAcquire()) throw new FileFailure(423, "FILE_TRANSFER_BUSY");
        String multipart = null;
        String stage="VALIDATE_CONTENT";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            if (bytes == 0) {
                if (content.read() != -1 || !HexFormat.of().formatHex(digest.digest()).equals(expectedSha256))
                    throw new FileFailure(400, "FILE_CONTENT_MISMATCH");
                stage="PUT_EMPTY";client.putObject(b -> b.bucket(bucket).key(key).metadata(Map.of("sha256", expectedSha256)), RequestBody.empty());
            } else {
                stage="CREATE_MULTIPART";multipart = client.createMultipartUpload(b -> b.bucket(bucket).key(key).metadata(Map.of("sha256", expectedSha256))).uploadId();
                byte[] buffer = new byte[PART_BYTES];
                List<CompletedPart> parts = new ArrayList<>();
                long remaining = bytes;
                int number = 1;
                while (remaining > 0) {
                    int length = (int) Math.min(buffer.length, remaining);
                    int count = 0;
                    while (count < length) {
                        int read = content.read(buffer, count, length - count);
                        if (read == -1) throw new FileFailure(400, "FILE_CONTENT_MISMATCH");
                        if (read == 0) continue;
                        count += read;
                    }
                    digest.update(buffer, 0, length);
                    String uploadId = multipart;
                    int partNumber = number++;
                    stage="UPLOAD_PART";var response = client.uploadPart(b -> b.bucket(bucket).key(key).uploadId(uploadId).partNumber(partNumber),
                            RequestBody.fromInputStream(new java.io.ByteArrayInputStream(buffer, 0, length), length));
                    parts.add(CompletedPart.builder().partNumber(partNumber).eTag(response.eTag()).build());
                    remaining -= length;
                }
                if (content.read() != -1 || !HexFormat.of().formatHex(digest.digest()).equals(expectedSha256))
                    throw new FileFailure(400, "FILE_CONTENT_MISMATCH");
                String uploadId = multipart;
                stage="COMPLETE_MULTIPART";client.completeMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(uploadId)
                        .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build()));
                multipart = null;
            }
            stage="VERIFY_METADATA";var identity = inspect(key);
            if (identity.bytes() != bytes || !expectedSha256.equals(identity.sha256()))
                throw new FileFailure(503, "ASSET_IDENTITY_MISMATCH");
            return identity;
        } catch (IOException failure) {
            throw new FileFailure(400, "FILE_STREAM_INTERRUPTED");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        } catch(software.amazon.awssdk.core.exception.SdkException unavailable) {
            throw unavailable(stage,unavailable);
        } finally {
            try {
                if (multipart != null) {
                    String uploadId = multipart;
                    try{client.abortMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(uploadId));}
                    catch(software.amazon.awssdk.core.exception.SdkException unavailable){throw unavailable("ABORT_MULTIPART",unavailable);}
                }
            } finally { transfer.release(); }
        }
    }

    private static FileFailure unavailable(String stage,software.amazon.awssdk.core.exception.SdkException failure) {
        Throwable root=failure;for(int i=0;i<8&&root.getCause()!=null&&root.getCause()!=root;i++)root=root.getCause();
        String text=String.valueOf(failure.getMessage()).toLowerCase(java.util.Locale.ROOT);
        String category=text.contains("timeout")||text.contains("timed out")?"TIMEOUT":
                text.contains("connect")?"CONNECTION":text.contains("xml")||text.contains("unmarshall")||text.contains("parse")?"CODEC":
                text.contains("checksum")||text.contains("digest")?"CHECKSUM":text.contains("credential")?"CREDENTIAL":
                text.contains("sign")||text.contains("hash")?"SIGNING":text.contains("length")?"LENGTH":"OTHER_CLIENT";
        String frames=java.util.Arrays.stream(failure.getStackTrace()).filter(f->f.getClassName().startsWith("software.amazon.awssdk."))
                .limit(4).map(f->f.getClassName()+"#"+f.getMethodName()).collect(java.util.stream.Collectors.joining(","));
        // Only fixed stages/categories and code identities: never log messages, keys, URLs, headers or bodies.
        LOG.warn("fileObjectFailure stage={} category={} sdkType={} causeType={} sdkFrames={}",stage,category,
                failure.getClass().getSimpleName(),root.getClass().getSimpleName(),frames);
        var result=new FileFailure(503,"FILE_OBJECT_WRITE_UNKNOWN");result.initCause(failure);return result;
    }

    @Override public Identity inspect(String key) {
        var head = client.headObject(b -> b.bucket(bucket).key(key));
        return new Identity(key, head.contentLength(), head.metadata().get("sha256"));
    }

    @Override public Content open(String key, Long first, Long last) {
        var request = GetObjectRequest.builder().bucket(bucket).key(key);
        if (first != null) request.range("bytes=" + first + "-" + (last == null ? "" : last));
        var stream = client.getObject(request.build());
        var response = stream.response();
        long total = response.contentLength();
        if (response.contentRange() != null) total = Long.parseLong(response.contentRange().substring(response.contentRange().lastIndexOf('/') + 1));
        return new Content(stream, response.contentLength(), total);
    }

    @Override public void deleteUncommitted(String key) {
        client.headBucket(b->b.bucket(bucket));
        client.deleteObject(b -> b.bucket(bucket).key(key));
        try {client.headObject(b->b.bucket(bucket).key(key));}
        catch(software.amazon.awssdk.services.s3.model.S3Exception absent) {
            if(absent.statusCode()==404){client.headBucket(b->b.bucket(bucket));return;}
            throw absent;
        }
        throw new FileFailure(503,"STAGING_DELETE_UNCONFIRMED");
    }
}
