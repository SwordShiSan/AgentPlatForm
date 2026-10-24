package com.agentplatform.app.files;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AuthorizedAssetStreamTest {
    @Test void verifiesContentAndNeverBuffersWholeFile() throws Exception {
        byte[] content="中文666".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        var checks=new AtomicInteger();
        try(var source=new AuthorizedAssetStream(new ByteArrayInputStream(content),content.length,hash,checks::incrementAndGet)) {
            assertArrayEquals(content,source.readAllBytes());assertTrue(checks.get()>=2);
        }
    }
    @Test void truncationAndHashMismatchCannotBecomeSuccessfulEof() throws Exception {
        try(var source=new AuthorizedAssetStream(new ByteArrayInputStream(new byte[1]),2,null,()->{})) {
            assertThrows(IOException.class,source::readAllBytes);
        }
        try(var source=new AuthorizedAssetStream(new ByteArrayInputStream(new byte[1]),1,"0".repeat(64),()->{})) {
            assertThrows(IOException.class,source::readAllBytes);
        }
    }
    @Test void revokedPermissionStopsReadBeforeBytesAreReturned() {
        var source=new AuthorizedAssetStream(new ByteArrayInputStream(new byte[2]),2,null,()->{throw new IllegalStateException("revoked");});
        assertThrows(IllegalStateException.class,()->source.read(new byte[2]));
    }
}
