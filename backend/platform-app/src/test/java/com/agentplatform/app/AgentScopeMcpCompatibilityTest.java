package com.agentplatform.app;

import static org.junit.jupiter.api.Assertions.*;

import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** SDK interoperability probe only: this is not the platform MCP endpoint. */
class AgentScopeMcpCompatibilityTest {
    @TempDir Path temp;

    @Test void discoversAndCallsARealSdkFixture() throws Exception {
        var mapper = McpJsonMapper.getDefault();
        var transport = HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(mapper).mcpEndpoint("/mcp").build();
        var tool = McpSchema.Tool.builder().name("fixture_echo").description("Deterministic fixture")
                .inputSchema(new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null)).build();
        var calls = new AtomicInteger();
        var server = McpServer.sync(transport).serverInfo("r62-fixture", "1")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tool(tool, (exchange, arguments) -> {
                    calls.incrementAndGet();
                    return new McpSchema.CallToolResult("fixture-ok", false);
                }).build();
        var tomcat = new Tomcat();
        tomcat.setBaseDir(temp.toString());
        tomcat.setPort(0);
        tomcat.getConnector().setProperty("address", "127.0.0.1");
        var context = tomcat.addContext("", temp.toString());
        Tomcat.addServlet(context, "mcp", transport).setAsyncSupported(true);
        context.addServletMappingDecoded("/mcp", "mcp");
        try {
            tomcat.start();
            int port = tomcat.getConnector().getLocalPort();
            int unavailableProxyPort;
            try (var socket = new ServerSocket(0)) { unavailableProxyPort = socket.getLocalPort(); }
            var proxyConfigured = new java.util.concurrent.atomic.AtomicBoolean();
            assertThrows(RuntimeException.class, () -> {
                try (var deniedClient = McpClientBuilder.create("r62-no-direct-fallback")
                        .streamableHttpTransport("http://127.0.0.1:" + port + "/mcp")
                        .customizeStreamableHttpClient(builder -> {
                            proxyConfigured.set(true);
                            builder.proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", unavailableProxyPort)));
                        })
                        .initializationTimeout(Duration.ofSeconds(3)).timeout(Duration.ofSeconds(3))
                        .buildAsync().block(Duration.ofSeconds(5))) {
                    assertNotNull(deniedClient);
                    deniedClient.initialize().block(Duration.ofSeconds(5));
                }
            });
            assertTrue(proxyConfigured.get(), "SDK must apply the configured HTTP client customization");
            assertEquals(0, calls.get(), "an unavailable proxy must not call the fixture tool");
            try (var client = McpClientBuilder.create("r62-fixture")
                    .streamableHttpTransport("http://127.0.0.1:" + port + "/mcp")
                    .initializationTimeout(Duration.ofSeconds(10)).timeout(Duration.ofSeconds(10))
                    .buildAsync().block(Duration.ofSeconds(15))) {
                assertNotNull(client);
                client.initialize().block(Duration.ofSeconds(15));
                var tools = client.listTools().block(Duration.ofSeconds(15));
                assertNotNull(tools);
                assertEquals(List.of("fixture_echo"), tools.stream().map(McpSchema.Tool::name).toList());
                assertEquals(0, calls.get(), "discovery must not call tools");
                var result = client.callTool("fixture_echo", Map.of()).block(Duration.ofSeconds(15));
                assertNotNull(result);
                assertFalse(result.isError());
                assertEquals(1, calls.get());
            }
        } finally {
            server.close();
            tomcat.stop();
            tomcat.destroy();
        }
    }
}
