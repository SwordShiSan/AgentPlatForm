package com.agentplatform.infrastructure.capability;

import com.agentplatform.domain.capability.McpEndpointPolicy;
import com.agentplatform.domain.files.FileFailure;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.modelcontextprotocol.json.McpJsonMapper;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Discovers a fixed external endpoint through the required proxy; it never calls a business tool. */
public final class McpDiscovery {
    public enum AuthKind { NONE, BEARER, API_KEY }
    public static final class Authentication {
        private final AuthKind kind; private final String header; private final String secret;
        public Authentication(AuthKind kind, String header, String secret) {
            this.kind = Objects.requireNonNull(kind); this.header = header; this.secret = secret;
            if (kind == AuthKind.NONE && (header != null || secret != null)
                    || kind == AuthKind.BEARER && (header != null || !validSecret(secret))
                    || kind == AuthKind.API_KEY && (!("X-API-Key".equals(header) || "api-key".equals(header)) || !validSecret(secret)))
                throw new IllegalArgumentException("MCP_AUTHENTICATION_INVALID");
        }
        private static boolean validSecret(String value) {
            return value != null && !value.isBlank() && value.length() <= 8192 && value.indexOf('\r') < 0 && value.indexOf('\n') < 0;
        }
    }
    public record Tool(String name, String description, String schemaJson, String schemaSha256) {}
    private final InetSocketAddress proxy;
    public McpDiscovery(InetSocketAddress proxy) {
        this.proxy = Objects.requireNonNull(proxy, "controlled proxy is required");
        if (proxy.getPort() < 1 || proxy.getPort() > 65535) throw new IllegalArgumentException("MCP_PROXY_INVALID");
    }
    public List<Tool> discover(String endpoint, Authentication authentication) {
        Objects.requireNonNull(authentication);
        var validated = McpEndpointPolicy.external(endpoint);
        var builder = McpClientBuilder.create("platform-mcp-discovery")
                .streamableHttpTransport(validated.toASCIIString())
                .customizeStreamableHttpClient(client -> client.proxy(ProxySelector.of(proxy)).followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(Duration.ofSeconds(10)))
                .initializationTimeout(Duration.ofSeconds(10)).timeout(Duration.ofSeconds(60));
        if (authentication.kind == AuthKind.BEARER) builder.header("Authorization", "Bearer " + authentication.secret);
        else if (authentication.kind == AuthKind.API_KEY) builder.header(authentication.header, authentication.secret);
        try (var client = builder.buildAsync().block(Duration.ofSeconds(15))) {
            if (client == null) throw new FileFailure(503, "MCP_DISCOVERY_UNAVAILABLE");
            client.initialize().block(Duration.ofSeconds(15));
            var tools = client.listTools().block(Duration.ofSeconds(65));
            if (tools == null || tools.size() > 100) throw new FileFailure(422, "MCP_TOOL_LIMIT");
            var mapper = McpJsonMapper.getDefault(); var result = new ArrayList<Tool>(); var names = new HashSet<String>();
            long total = 0;
            for (var tool : tools) {
                if (tool.name() == null || tool.name().isBlank() || tool.name().getBytes(StandardCharsets.UTF_8).length > 512
                        || !names.add(tool.name()) || tool.inputSchema() == null) throw new FileFailure(422, "MCP_TOOL_INVALID");
                String schema = mapper.writeValueAsString(tool.inputSchema()); byte[] bytes = schema.getBytes(StandardCharsets.UTF_8);
                total += bytes.length;
                if (bytes.length > 65536 || total > 1048576) throw new FileFailure(422, "MCP_SCHEMA_LIMIT");
                String description = tool.description();
                if (description != null && description.getBytes(StandardCharsets.UTF_8).length > 65536)
                    throw new FileFailure(422, "MCP_TOOL_INVALID");
                result.add(new Tool(tool.name(), description, schema, sha256(bytes)));
            }
            return List.copyOf(result);
        } catch (FileFailure failure) { throw failure; }
        catch (Exception unavailable) { throw new FileFailure(503, "MCP_DISCOVERY_UNAVAILABLE"); }
    }
    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
