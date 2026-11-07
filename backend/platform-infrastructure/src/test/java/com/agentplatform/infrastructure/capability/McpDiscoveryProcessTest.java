package com.agentplatform.infrastructure.capability;

import static org.junit.jupiter.api.Assertions.*;
import com.agentplatform.domain.files.FileFailure;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpDiscoveryProcessTest {
    @TempDir Path temporary;
    @Test void missingOperatorTrustStoreIsRejectedBeforeStartingAChild() {
        assertThrows(IllegalArgumentException.class, () -> new McpDiscoveryProcess(new InetSocketAddress("127.0.0.1", 12345), temporary.resolve("missing.jks")));
    }
    @Test void actualChildUsesOnlyConfiguredProxyAndDoesNotExposeCredential() throws Exception {
        try (var listener = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            var request = CompletableFuture.supplyAsync(() -> {
                try (var socket = listener.accept()) {
                    socket.setSoTimeout(5000);
                    var reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    String first = reader.readLine();
                    socket.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush(); return first;
                } catch (Exception failure) { throw new IllegalStateException(failure); }
            });
            var process = new McpDiscoveryProcess(new InetSocketAddress("127.0.0.1", listener.getLocalPort()));
            var auth = new McpDiscovery.Authentication(McpDiscovery.AuthKind.BEARER, null, "private-fixture-sentinel");
            var failure = assertThrows(FileFailure.class, () -> process.discover("https://fixture.invalid/mcp", auth));
            assertEquals(503, failure.status()); assertFalse(failure.toString().contains("private-fixture-sentinel"));
            assertTrue(request.get(15, TimeUnit.SECONDS).startsWith("CONNECT fixture.invalid:443 "));
        }
    }
    @Test void timeoutStopsExactChildBeforeSlotCanBeReused() throws Exception {
        Path pid = temporary.resolve("pid");
        var process = fixture("sleep", pid, Duration.ofSeconds(2), 1024);
        var auth = new McpDiscovery.Authentication(McpDiscovery.AuthKind.NONE, null, null);
        assertEquals(503, assertThrows(FileFailure.class, () -> process.discover("https://fixture.invalid/mcp", auth)).status());
        long original = Long.parseLong(Files.readString(pid));
        assertFalse(ProcessHandle.of(original).map(ProcessHandle::isAlive).orElse(false));
        assertEquals(503, assertThrows(FileFailure.class, () -> process.discover("https://fixture.invalid/mcp", auth)).status());
        long next = Long.parseLong(Files.readString(pid));
        assertNotEquals(original, next, "a confirmed stopped child must not poison the slot");
        assertFalse(ProcessHandle.of(next).map(ProcessHandle::isAlive).orElse(false));
    }
    @Test void oversizedChildOutputIsRejectedAndChildIsStopped() throws Exception {
        Path pid = temporary.resolve("pid");
        var process = fixture("oversize", pid, Duration.ofSeconds(10), 1024);
        var auth = new McpDiscovery.Authentication(McpDiscovery.AuthKind.NONE, null, null);
        assertEquals(503, assertThrows(FileFailure.class, () -> process.discover("https://fixture.invalid/mcp", auth)).status());
        assertFalse(ProcessHandle.of(Long.parseLong(Files.readString(pid))).map(ProcessHandle::isAlive).orElse(false));
    }
    @Test void boundedChildResultCanBeReadWithoutLeakingTheCredential() {
        var process = fixture("valid", temporary.resolve("pid"), Duration.ofSeconds(10), 4096);
        var auth = new McpDiscovery.Authentication(McpDiscovery.AuthKind.BEARER, null, "private-fixture-sentinel");
        var tools = process.discover("https://fixture.invalid/mcp", auth);
        assertEquals(1, tools.size()); assertEquals("fixture", tools.getFirst().name());
        assertFalse(tools.toString().contains("private-fixture-sentinel"));
    }
    private McpDiscoveryProcess fixture(String action, Path pid, Duration timeout, int maxOutput) {
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        return new McpDiscoveryProcess(new InetSocketAddress("127.0.0.1", 12345), timeout, maxOutput,
                List.of(java, "-Xmx32m", "-cp", cp, Fixture.class.getName(), action, pid.toString()));
    }
    public static final class Fixture {
        public static void main(String[] args) throws Exception {
            System.in.readAllBytes(); Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
            if ("valid".equals(args[0])) {
                System.out.print("{\"status\":200,\"code\":null,\"tools\":[{\"name\":\"fixture\",\"description\":\"ok\",\"schemaJson\":\"{}\",\"schemaSha256\":\"" + "a".repeat(64) + "\"}]}");
            } else if ("sleep".equals(args[0])) Thread.sleep(30000);
            else { System.out.write(new byte[16384]); System.out.flush(); Thread.sleep(30000); }
        }
    }
}
