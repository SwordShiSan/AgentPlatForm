package com.agentplatform.infrastructure.capability;

import static org.junit.jupiter.api.Assertions.*;
import com.agentplatform.domain.files.FileFailure;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class McpDiscoveryTest {
    @Test void credentialsAreBoundedAndNeverAppearInTheirObjectDescription() {
        var auth = new McpDiscovery.Authentication(McpDiscovery.AuthKind.BEARER, null, "sentinel-private-value");
        assertFalse(auth.toString().contains("sentinel-private-value"));
        assertThrows(IllegalArgumentException.class, () -> new McpDiscovery.Authentication(McpDiscovery.AuthKind.API_KEY, "Authorization", "value"));
        assertThrows(IllegalArgumentException.class, () -> new McpDiscovery.Authentication(McpDiscovery.AuthKind.BEARER, null, "bad\r\nheader"));
    }
    @Test void failedControlledProxyDoesNotFallBackToDirectConnection() throws Exception {
        try (var listener = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            var observed = CompletableFuture.supplyAsync(() -> {
                try (var socket = listener.accept()) {
                    socket.setSoTimeout(5000);
                    var input = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    String line = input.readLine();
                    socket.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush(); return line;
                } catch (Exception failure) { throw new IllegalStateException(failure); }
            });
            var discovery = new McpDiscovery(new InetSocketAddress("127.0.0.1", listener.getLocalPort()));
            var auth = new McpDiscovery.Authentication(McpDiscovery.AuthKind.NONE, null, null);
            assertEquals(503, assertThrows(FileFailure.class, () -> discovery.discover("https://fixture.invalid/mcp", auth)).status());
            assertTrue(observed.get(10, TimeUnit.SECONDS).startsWith("CONNECT fixture.invalid:443 "));
        }
    }
}
