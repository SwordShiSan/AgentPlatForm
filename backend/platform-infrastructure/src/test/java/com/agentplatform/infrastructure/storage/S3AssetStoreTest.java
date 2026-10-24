package com.agentplatform.infrastructure.storage;

import com.agentplatform.domain.files.FileFailure;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import software.amazon.awssdk.core.sync.RequestBody;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class S3AssetStoreTest {
    @Test void sdkFailureHasAStableUnknownCodeAndNeverLogsItsMessageOrObjectIdentity() throws Exception {
        var client=mock(S3Client.class);String sentinel="sentinel-private-key-and-endpoint";
        var sdk=software.amazon.awssdk.core.exception.SdkClientException.create(sentinel);
        when(client.createMultipartUpload(org.mockito.ArgumentMatchers.<Consumer<CreateMultipartUploadRequest.Builder>>any())).thenThrow(sdk);
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(S3AssetStore.class);
        var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();appender.start();logger.addAppender(appender);
        try {
            var failure=assertThrows(FileFailure.class,()->new S3AssetStore(client,"private").put("private-object-name",1,hash(new byte[]{1}),new ByteArrayInputStream(new byte[]{1})));
            assertEquals(503,failure.status());assertEquals("FILE_OBJECT_WRITE_UNKNOWN",failure.getMessage());assertSame(sdk,failure.getCause());
            String logs=appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).collect(java.util.stream.Collectors.joining());
            assertTrue(logs.contains("stage=CREATE_MULTIPART"));assertFalse(logs.contains(sentinel));assertFalse(logs.contains("private-object-name"));
            assertTrue(appender.list.stream().allMatch(event->event.getThrowableProxy()==null));
        }finally{logger.detachAppender(appender);appender.stop();}
    }
    private static String hash(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
    @Test void validatesBeforeCreatingAnObject() {
        var client = mock(S3Client.class);
        var store = new S3AssetStore(client, "private");
        assertThrows(FileFailure.class, () -> store.put("key", S3AssetStore.MAX_BYTES + 1, "0".repeat(64), new ByteArrayInputStream(new byte[0])));
        verifyNoInteractions(client);
    }
    @Test void wrongEmptyHashCannotCreateAnObject() {
        var client = mock(S3Client.class);
        assertThrows(FileFailure.class, () -> new S3AssetStore(client, "private").put("key", 0, "0".repeat(64), new ByteArrayInputStream(new byte[0])));
        verifyNoInteractions(client);
    }
    @Test void everyPartAndCompletionRetainTheirOwnNumberSizeAndBytes() throws Exception {
        var client = mock(S3Client.class);
        List<Integer> numbers = new ArrayList<>();
        List<Long> sizes = new ArrayList<>();
        long bytes = 20L * 1024 * 1024;
        var pattern = new java.io.InputStream() {
            long remaining = bytes;
            @Override public int read() { if (remaining-- > 0) return 102; return -1; }
            @Override public int read(byte[] target, int offset, int length) {
                if (remaining <= 0) return -1;
                int count = (int)Math.min(length, remaining);
                java.util.Arrays.fill(target,offset,offset+count,(byte)102);remaining-=count;return count;
            }
        };
        var md = MessageDigest.getInstance("SHA-256");
        byte[] chunk = new byte[65536];java.util.Arrays.fill(chunk,(byte)102);
        for(long i=0;i<bytes/chunk.length;i++)md.update(chunk);
        String expected = HexFormat.of().formatHex(md.digest());
        when(client.createMultipartUpload(org.mockito.ArgumentMatchers.<Consumer<CreateMultipartUploadRequest.Builder>>any()))
                .thenReturn(CreateMultipartUploadResponse.builder().uploadId("original-upload").build());
        when(client.uploadPart(org.mockito.ArgumentMatchers.<Consumer<UploadPartRequest.Builder>>any(), any(RequestBody.class)))
                .thenAnswer(invocation -> {
                    Consumer<UploadPartRequest.Builder> configure = invocation.getArgument(0);
                    var builder = UploadPartRequest.builder();configure.accept(builder);var request = builder.build();
                    numbers.add(request.partNumber());
                    RequestBody body = invocation.getArgument(1);
                    try(var stream=body.contentStreamProvider().newStream()) {
                        long count=0;byte[] buffer=new byte[65536];int read;
                        while((read=stream.read(buffer))!=-1) {for(int i=0;i<read;i++)assertEquals(102,buffer[i]);count+=read;}
                        sizes.add(count);
                    }
                    return UploadPartResponse.builder().eTag("part-"+request.partNumber()).build();
                });
        when(client.completeMultipartUpload(org.mockito.ArgumentMatchers.<Consumer<CompleteMultipartUploadRequest.Builder>>any()))
                .thenAnswer(invocation -> {
                    Consumer<CompleteMultipartUploadRequest.Builder> configure=invocation.getArgument(0);
                    var builder=CompleteMultipartUploadRequest.builder();configure.accept(builder);
                    assertEquals(List.of(1,2,3),builder.build().multipartUpload().parts().stream().map(CompletedPart::partNumber).toList());
                    return CompleteMultipartUploadResponse.builder().build();
                });
        when(client.headObject(org.mockito.ArgumentMatchers.<Consumer<HeadObjectRequest.Builder>>any()))
                .thenReturn(HeadObjectResponse.builder().contentLength(bytes).metadata(Map.of("sha256",expected)).build());
        new S3AssetStore(client,"private").put("key",bytes,expected,pattern);
        assertEquals(List.of(1,2,3),numbers);
        assertEquals(List.of(8L*1024*1024,8L*1024*1024,4L*1024*1024),sizes);
    }
}
