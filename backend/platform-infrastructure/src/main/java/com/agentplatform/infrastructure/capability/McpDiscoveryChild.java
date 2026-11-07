package com.agentplatform.infrastructure.capability;

import com.agentplatform.domain.files.FileFailure;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/** Trusted, memory-bounded discovery process. The parent is its only caller. */
public final class McpDiscoveryChild {
    private static final int MAX_INPUT = 131072;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    public record Input(String endpoint, String authKind, String header, String secret, String proxyHost, int proxyPort) {
        @Override public String toString() { return "Input[redacted]"; }
    }
    public record Result(int status, String code, List<McpDiscovery.Tool> tools) {}
    private McpDiscoveryChild() {}

    public static void main(String[] args) throws Exception {
        // Framework logging must never contaminate the protocol or expose remote input.
        var protocol = new FileOutputStream(FileDescriptor.out);
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        Result result;
        try {
            byte[] bytes = System.in.readNBytes(MAX_INPUT + 1);
            if (bytes.length > MAX_INPUT) throw new IllegalArgumentException("INPUT_LIMIT");
            Input input;
            try { input = JSON.readValue(bytes, Input.class); }
            finally { java.util.Arrays.fill(bytes, (byte) 0); }
            var authentication = new McpDiscovery.Authentication(McpDiscovery.AuthKind.valueOf(input.authKind()), input.header(), input.secret());
            var discovery = new McpDiscovery(InetSocketAddress.createUnresolved(input.proxyHost(), input.proxyPort()));
            result = new Result(200, null, discovery.discover(input.endpoint(), authentication));
        } catch (FileFailure failure) {
            String code = failure.getMessage();
            if (code == null || !code.matches("[A-Z0-9_]{1,64}")) code = "MCP_DISCOVERY_UNAVAILABLE";
            result = new Result(failure.status() == 422 ? 422 : 503, code, List.of());
        } catch (Exception failure) {
            result = new Result(503, "MCP_DISCOVERY_UNAVAILABLE", List.of());
        }
        protocol.write(JSON.writeValueAsBytes(result)); protocol.flush();
        System.exit(0);
    }
}
