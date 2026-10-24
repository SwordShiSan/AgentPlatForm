package com.agentplatform.infrastructure.workspace;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** Official SDK transport contract only. No Docker socket, container or XFS is used. */
class DockerStdinFramingTest {
    @Test @Timeout(60)
    void exactFramesCompleteWithoutTransportHalfClose() throws Exception {
        exchange(0);
        exchange(100L * 1024 * 1024);
    }

    private void exchange(long size) throws Exception {
        byte[] header = "fixed-frame\n".getBytes(StandardCharsets.UTF_8);
        byte[] trailer = digest(size);
        var served = new CompletableFuture<Void>();
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(30000);
            Thread peer = new Thread(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(30000);
                    var input = socket.getInputStream();
                    String request = headers(input);
                    assertTrue(request.startsWith("POST /v1.45/exec/frame-test/start HTTP/1.1"));
                    int contentLength = Arrays.stream(request.split("\r\n"))
                            .filter(line -> line.toLowerCase().startsWith("content-length:"))
                            .mapToInt(line -> Integer.parseInt(line.substring(line.indexOf(':') + 1).trim()))
                            .findFirst().orElseThrow();
                    assertEquals(contentLength, input.readNBytes(contentLength).length);
                    var output = socket.getOutputStream();
                    output.write(("HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\n"
                            + "Upgrade: tcp\r\nContent-Type: application/vnd.docker.raw-stream\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    output.flush();
                    assertArrayEquals(header, input.readNBytes(header.length));
                    var observed = MessageDigest.getInstance("SHA-256");
                    byte[] buffer = new byte[64 * 1024];
                    long remaining = size;
                    while (remaining > 0) {
                        int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                        assertTrue(count > 0);
                        observed.update(buffer, 0, count);
                        remaining -= count;
                    }
                    assertArrayEquals(trailer, observed.digest());
                    assertArrayEquals(trailer, input.readNBytes(32));
                    // Return immediately after the finite frame; never await socket EOF.
                    output.write(new byte[]{1, 0, 0, 0, 0, 0, 0, 2, 'O', 'K'});
                    output.flush();
                    served.complete(null);
                } catch (Throwable failure) {
                    served.completeExceptionally(failure);
                }
            }, "fixed-docker-stdin-peer");
            peer.setDaemon(true);
            peer.start();
            var config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                    .withDockerHost("tcp://127.0.0.1:" + server.getLocalPort()).withApiVersion("1.45").build();
            var received = new ByteArrayOutputStream();
            try (var transport = new ApacheDockerHttpClient.Builder().dockerHost(config.getDockerHost())
                    .connectionTimeout(Duration.ofSeconds(2)).responseTimeout(Duration.ofSeconds(30)).build();
                 var docker = DockerClientImpl.getInstance(config, transport);
                 var callback = new ResultCallback.Adapter<Frame>() {
                     @Override public void onNext(Frame frame) {
                         received.writeBytes(frame.getPayload());
                     }
                 };
                 var source = new SequenceInputStream(new ByteArrayInputStream(header),
                         new SequenceInputStream(new RepeatedInput(size), new ByteArrayInputStream(trailer)))) {
                docker.execStartCmd("frame-test").withTty(false).withDetach(false).withStdIn(source).exec(callback);
                assertTrue(callback.awaitCompletion(35, TimeUnit.SECONDS));
                served.get(5, TimeUnit.SECONDS);
                assertEquals("OK", received.toString(StandardCharsets.US_ASCII));
                peer.join(1000);
                assertFalse(peer.isAlive());
            }
        }
    }

    private static String headers(InputStream input) throws IOException {
        var bytes = new ByteArrayOutputStream();
        while (bytes.size() < 16384) {
            int next = input.read();
            if (next < 0) throw new EOFException();
            bytes.write(next);
            if (bytes.size() >= 4) {
                byte[] value = bytes.toByteArray();
                int end = value.length;
                if (value[end - 4] == '\r' && value[end - 3] == '\n'
                        && value[end - 2] == '\r' && value[end - 1] == '\n')
                    return bytes.toString(StandardCharsets.US_ASCII);
            }
        }
        throw new IOException("Fixture HTTP header exceeds bound");
    }

    private static byte[] digest(long size) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var source = new RepeatedInput(size)) {
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = source.read(buffer)) >= 0;) digest.update(buffer, 0, count);
        }
        return digest.digest();
    }

    private static final class RepeatedInput extends InputStream {
        private long remaining;
        RepeatedInput(long size) { remaining = size; }
        @Override public int read() { if (remaining == 0) return -1; remaining--; return 'z'; }
        @Override public int read(byte[] buffer, int offset, int length) {
            if (length == 0) return 0;
            if (remaining == 0) return -1;
            int count = (int) Math.min(length, remaining);
            Arrays.fill(buffer, offset, offset + count, (byte) 'z');
            remaining -= count;
            return count;
        }
    }
}
