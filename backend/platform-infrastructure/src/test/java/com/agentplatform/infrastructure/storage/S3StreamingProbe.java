package com.agentplatform.infrastructure.storage;

import java.io.InputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.util.HexFormat;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/** Explicit transport experiment, not application acceptance. Credentials only from protected environment. */
public final class S3StreamingProbe {
    private static final class Pattern extends InputStream {
        private long remaining;
        Pattern(long bytes) { remaining = bytes; }
        @Override public int read() { if (remaining == 0) return -1; remaining--; return 102; }
        @Override public int read(byte[] target, int offset, int length) {
            if (remaining == 0) return -1;
            int count = (int) Math.min(length, remaining);
            java.util.Arrays.fill(target, offset, offset + count, (byte)102); remaining -= count; return count;
        }
    }
    private static String digest(long bytes) throws Exception {
        var md = MessageDigest.getInstance("SHA-256");
        try (var in = new Pattern(bytes)) {
            byte[] buffer = new byte[65536]; int read;
            while ((read = in.read(buffer)) != -1) md.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(md.digest());
    }
    public static void main(String[] args) throws Exception {
        String bucket = "r33-stream-probe";
        try (var client = S3Clients.builder(URI.create(System.getenv("R33_S3_ENDPOINT")),
                StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        System.getenv("R33_S3_ACCESS"), System.getenv("R33_S3_SECRET"))))
                .overrideConfiguration(b -> b.addExecutionInterceptor(new software.amazon.awssdk.core.interceptor.ExecutionInterceptor() {
                    @Override public void beforeTransmission(software.amazon.awssdk.core.interceptor.Context.BeforeTransmission context,
                            software.amazon.awssdk.core.interceptor.ExecutionAttributes attributes) {
                        if (context.request() instanceof software.amazon.awssdk.services.s3.model.GetObjectRequest)
                            System.out.println("GET range="+context.httpRequest().firstMatchingHeader("Range")+", query="+context.httpRequest().rawQueryParameters());
                    }
                    @Override public void afterTransmission(software.amazon.awssdk.core.interceptor.Context.AfterTransmission context,
                            software.amazon.awssdk.core.interceptor.ExecutionAttributes attributes) {
                        if (context.request() instanceof software.amazon.awssdk.services.s3.model.GetObjectRequest)
                            System.out.println("GET response="+context.httpResponse().statusCode()+", length="+context.httpResponse().firstMatchingHeader("Content-Length")+", range="+context.httpResponse().firstMatchingHeader("Content-Range"));
                    }
                })).build()) {
            client.createBucket(b -> b.bucket(bucket));
            var store = new S3AssetStore(client, bucket);
            for (long size : new long[]{0, 100L * 1024 * 1024}) {
                String key = "probe/" + size;
                String expected = digest(size);
                var saved = store.put(key, size, expected, new Pattern(size));
                if (saved.bytes() != size || !saved.sha256().equals(expected)) throw new AssertionError("Object identity");
                try (var content = store.open(key, null, null)) {
                    var hash = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[65536]; long count=0; int n;
                    while ((n=content.stream().read(buffer))!=-1) { hash.update(buffer,0,n); count+=n; }
                    String actualHash = HexFormat.of().formatHex(hash.digest());
                    if (count != size || !actualHash.equals(expected)) throw new AssertionError("Read hash: expectedBytes="+size+", actualBytes="+count+", actualHash="+actualHash);
                    System.out.println("Verified streamed object bytes="+size+" SHA256="+actualHash);
                }
                if (size > 0) try (var range = store.open(key, 7L, 12L)) {
                    if (range.bytes()!=6 || range.totalBytes()!=size || range.stream().readAllBytes().length!=6)
                        throw new AssertionError("Range");
                }
            }
            try {
                store.put("probe/truncated", 8, digest(8), new Pattern(7));
                throw new AssertionError("Truncated content accepted");
            } catch (com.agentplatform.domain.files.FileFailure expected) {
                if (!expected.getMessage().equals("FILE_CONTENT_MISMATCH")) throw expected;
            }
            if (!client.listMultipartUploads(b -> b.bucket(bucket)).uploads().isEmpty()) throw new AssertionError("Leaked multipart");
            System.out.println("{\"status\":\"PASS\",\"maxFileBytes\":104857600,\"heapLimitMiB\":64,\"zeroAndRangeHashAndTruncation\":true,\"supplierCalls\":0}");
        }
    }
}
