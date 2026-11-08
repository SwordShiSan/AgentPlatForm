package com.agentplatform.infrastructure.harness.agentscope;

import static org.junit.jupiter.api.Assertions.*;

import com.agentplatform.domain.harness.HarnessPorts.ModelGateway;
import com.agentplatform.types.harness.HarnessData.*;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** SDK/Harness interoperability fixture: the journal is a local probe, not a PostgreSQL ledger. */
class McpGuardInteropTest {
    @TempDir Path root;

    @Test void realMcpCallOccursOnlyAfterGuardAndJournalAdmission() throws Exception {
        var transport = HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(McpJsonMapper.getDefault()).mcpEndpoint("/mcp").build();
        var schema = new McpSchema.JsonSchema("object", Map.of("value", Map.of("type", "string")),
                List.of("value"), false, null, null);
        var definition = McpSchema.Tool.builder().name("fixture_echo").description("Fixture")
                .inputSchema(schema).build();
        var remoteCalls = new AtomicInteger();
        var server = McpServer.sync(transport).serverInfo("r62-guard-fixture", "1")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tool(definition, (exchange, arguments) -> {
                    remoteCalls.incrementAndGet();
                    return new McpSchema.CallToolResult("echo:" + arguments.get("value"), false);
                }).build();
        var tomcat = new Tomcat();
        tomcat.setBaseDir(root.resolve("tomcat").toString());
        tomcat.setPort(0);
        tomcat.getConnector().setProperty("address", "127.0.0.1");
        var context = tomcat.addContext("", root.toString());
        Tomcat.addServlet(context, "mcp", transport).setAsyncSupported(true);
        context.addServletMappingDecoded("/mcp", "mcp");
        try {
            tomcat.start();
            try (var client = McpClientBuilder.create("r62-guard-fixture")
                    .streamableHttpTransport("http://127.0.0.1:" + tomcat.getConnector().getLocalPort() + "/mcp")
                    .timeout(Duration.ofSeconds(10)).buildAsync().block(Duration.ofSeconds(15))) {
                assertNotNull(client);
                client.initialize().block(Duration.ofSeconds(15));
                assertEquals(1, client.listTools().block(Duration.ofSeconds(15)).size());
                assertEquals(0, remoteCalls.get());
                var scope = FileProbeStore.scope();
                var store = new FileProbeStore(root.resolve("state"));
                var rounds = new AtomicInteger();
                var checks = new AtomicInteger();
                ModelGateway model = (s, messages, tools) -> rounds.incrementAndGet() == 1
                        ? CompletableFuture.completedFuture(new Round("", List.of(FileProbeStore.call("one")), "tool_calls", 1, 1))
                        : CompletableFuture.completedFuture(new Round("completed", List.of(), "stop", 1, 1));
                var factory = new AgentScopeRuntimeFactory(model, (s, call) -> {
                    checks.incrementAndGet(); return Decision.ALLOW;
                }, (s, call) -> {
                    assertEquals(OperationStatus.RUNNING, store.journal(s).operations().get(call.id()).status());
                    return client.callTool("fixture_echo", call.arguments()).toFuture().thenApply(result ->
                            new Observation(call.id(), call.name(), result.content().toString(),
                                    result.isError() ? OperationStatus.FAILED : OperationStatus.SUCCEEDED));
                }, store, store, FileProbeStore::definitions, root.resolve("workspace"));
                try (var execution = factory.prepare(new Request(scope, Bundle.baseline(),
                        List.of(Message.text(Role.USER, "run the fixture")), false, List.of(), 1), store)) {
                    assertEquals(Completion.PASS, execution.start().toCompletableFuture().get(20, TimeUnit.SECONDS).completion());
                }
                assertTrue(checks.get() >= 2, "permission is checked at discovery and execution");
                assertEquals(1, remoteCalls.get());
                assertEquals(OperationStatus.SUCCEEDED, store.journal(scope).operations().get("one").status());

                var deniedStore = new FileProbeStore(root.resolve("denied-state"));
                var deniedRounds = new AtomicInteger();
                ModelGateway deniedModel = (s, messages, tools) -> deniedRounds.incrementAndGet() == 1
                        ? CompletableFuture.completedFuture(new Round("", List.of(FileProbeStore.call("denied")), "tool_calls", 1, 1))
                        : CompletableFuture.completedFuture(new Round("denied", List.of(), "stop", 1, 1));
                var deniedFactory = new AgentScopeRuntimeFactory(deniedModel, (s, call) -> Decision.DENY,
                        (s, call) -> { throw new AssertionError("Denied tool must not execute"); },
                        deniedStore, deniedStore, FileProbeStore::definitions, root.resolve("workspace-denied"));
                try (var deniedRun = deniedFactory.prepare(new Request(scope, Bundle.baseline(),
                        List.of(Message.text(Role.USER, "run the fixture")), false, List.of(), 1), deniedStore)) {
                    deniedRun.start().toCompletableFuture().get(20, TimeUnit.SECONDS);
                }
                assertEquals(1, remoteCalls.get(), "denied operation must not reach MCP server");
                assertEquals(OperationStatus.DENIED, deniedStore.journal(scope).operations().get("denied").status());
            }
        } finally {
            server.close();
            tomcat.stop();
            tomcat.destroy();
        }
    }
}
