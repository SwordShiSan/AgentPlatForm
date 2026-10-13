package com.agentplatform.infrastructure.harness.agentscope;

import com.agentplatform.domain.harness.HarnessPorts.*;
import com.agentplatform.types.harness.HarnessData.*;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.state.State;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ProviderAndStateProbeTest {
    @TempDir Path root;
    public record ValueState(String value) implements State {}
    private Request request(Scope scope) { return new Request(scope, Bundle.baseline(), List.of(Message.text(Role.USER, "perform probes")), false, List.of(), 1); }
    private AgentScopeRuntimeFactory factory(FileProbeStore store, ModelGateway gateway, PolicyPort policy) {
        return new AgentScopeRuntimeFactory(gateway, policy, store, store, store, FileProbeStore.definitions(), root.resolve("workspace"));
    }
    @Test void realOpenAiExtensionDecodesLoopbackSseAndUsage() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new AtomicInteger();
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            String body = "data: {\"id\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"hel\"},\"finish_reason\":null}]}\n\n"
                    + "data: {\"id\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"lo\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}\n\n"
                    + "data: [DONE]\n\n";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream"); exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var model = OpenAIChatModel.builder().apiKey("fixture-not-a-secret").baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .modelName("fixture").stream(true).build();
            var responses = model.stream(List.of(Msg.builder().role(MsgRole.USER).textContent("hello").build()), List.of(), GenerateOptions.builder().build())
                    .collectList().block(Duration.ofSeconds(10));
            assertNotNull(responses); assertEquals(1, requests.get());
            String text = responses.stream().flatMap(response -> response.getContent().stream()).filter(TextBlock.class::isInstance).map(TextBlock.class::cast).map(TextBlock::getText).reduce("", String::concat);
            assertEquals("hello", text);
            assertTrue(responses.stream().anyMatch(response -> "stop".equals(response.getFinishReason())));
            assertTrue(responses.stream().filter(response -> response.getUsage() != null).anyMatch(response -> response.getUsage().getInputTokens() == 3 && response.getUsage().getOutputTokens() == 2));
        } finally { server.stop(0); }
    }
    @Test void casCompetitionHasExactlyOneWinner() throws Exception {
        var store = new FileProbeStore(root); Scope scope = FileProbeStore.scope(); var adapter = new ScopeStateStore(store, scope);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var writes = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 2; i++) writes.add(workers.submit(() -> {
                gate.await();
                try { adapter.saveIfVersion(scope.actorId(), scope.conversationId(), "competition", new ValueState("winner"), 0); return true; }
                catch (IllegalStateException expected) { assertEquals("STATE_CAS_CONFLICT", expected.getMessage()); return false; }
            }));
            gate.countDown(); int winners = 0; for (var write : writes) if (write.get(5, TimeUnit.SECONDS)) winners++;
            assertEquals(1, winners); assertEquals(1, adapter.getVersioned(scope.actorId(), scope.conversationId(), "competition", ValueState.class).version());
        }
    }
    @Test void concurrentSpacesWithSameUserAndSessionCannotShareState() throws Exception {
        var store = new FileProbeStore(root); Scope a = FileProbeStore.scope();
        Scope b = new Scope("0199db83-0000-7000-8000-000000000099", a.actorId(), a.conversationId(), a.taskId(), a.attemptId(), 1, false);
        try (var first = factory(store, store.model(), store.policy()).prepare(request(a), store);
             var second = factory(store, store.model(), store.policy()).prepare(request(b), store)) {
            var one = first.start().toCompletableFuture(); var two = second.start().toCompletableFuture();
            assertEquals(Completion.PASS, one.get(12, TimeUnit.SECONDS).completion()); assertEquals(Completion.PASS, two.get(12, TimeUnit.SECONDS).completion());
        }
        assertEquals(2, store.journal(a).effects()); assertEquals(2, store.journal(b).effects());
        new ScopeStateStore(store, a).delete(a.actorId(), a.conversationId());
        assertFalse(new ScopeStateStore(store, a).exists(a.actorId(), a.conversationId()));
        assertTrue(new ScopeStateStore(store, b).exists(b.actorId(), b.conversationId()));
    }
    @Test void revocationBetweenPermissionAndExecutionPreventsEffect() {
        var store = new FileProbeStore(root); var decisions = new AtomicInteger();
        PolicyPort policy = (scope, call) -> decisions.incrementAndGet() > 2 ? Decision.DENY : Decision.ALLOW;
        try (var run = factory(store, store.model(), policy).prepare(request(FileProbeStore.scope()), store)) {
            assertThrows(ExecutionException.class, () -> run.start().toCompletableFuture().get(10, TimeUnit.SECONDS));
        }
        assertEquals(0, store.journal(FileProbeStore.scope()).effects());
    }
    @Test void interruptStopsPendingModelAndNoDefaultFilesystemOrMemoryWrites() throws Exception {
        var store = new FileProbeStore(root); var entered = new CountDownLatch(1); var pending = new CompletableFuture<Round>();
        ModelGateway gateway = (scope, messages, tools) -> { entered.countDown(); return pending; };
        try (var run = factory(store, gateway, store.policy()).prepare(request(FileProbeStore.scope()), store)) {
            var result = run.start().toCompletableFuture(); assertTrue(entered.await(3, TimeUnit.SECONDS));
            run.interrupt(); assertEquals(Completion.CANCELLED, result.get(5, TimeUnit.SECONDS).completion());
            assertTrue(pending.isCancelled());
        }
        assertEquals(0, store.journal(FileProbeStore.scope()).effects());
        try (var files = java.nio.file.Files.walk(root)) {
            assertTrue(files.noneMatch(file -> Set.of("MEMORY.md", "AGENTS.md", "transcripts").contains(file.getFileName().toString())));
        }
    }
    @Test void unknownBlockAndMalformedToolJsonAreRejected() {
        var codec = new PlatformMessageCodec();
        var unknown = Msg.builder().role(MsgRole.ASSISTANT).content(ImageBlock.builder().source(URLSource.builder().url("http://fixture.invalid/image").build()).build()).build();
        assertThrows(IllegalArgumentException.class, () -> codec.decode(unknown, Map.of()));
        var malformed = ToolUseBlock.builder().id("bad").name("write_probe").content("{invalid").build();
        assertThrows(IllegalArgumentException.class, () -> codec.decodeCall(malformed, Map.of("write_probe", FileProbeStore.definitions().getFirst())));
    }
    @Test void incompleteManifestAndCorruptStateCannotSilentlyRestore() throws Exception {
        var store = new FileProbeStore(root); Scope scope = FileProbeStore.scope(); var adapter = new ScopeStateStore(store, scope);
        adapter.save(scope.actorId(), scope.conversationId(), "one", new ValueState("one"));
        adapter.save(scope.actorId(), scope.conversationId(), "two", new ValueState("two"));
        store.seal(scope, Bundle.baseline()); store.verifyRestore(scope, Bundle.baseline());
        adapter.delete(scope.actorId(), scope.conversationId(), "two");
        assertThrows(IllegalStateException.class, () -> store.verifyRestore(scope, Bundle.baseline()));
        store.compareAndSwap(scope, "one", -1, "{invalid".getBytes(StandardCharsets.UTF_8));
        assertThrows(RuntimeException.class, () -> adapter.get(scope.actorId(), scope.conversationId(), "one", ValueState.class));
        var foreign = io.agentscope.core.state.AgentState.builder().userId("foreign").sessionId(scope.conversationId()).build();
        assertThrows(SecurityException.class, () -> adapter.save(scope.actorId(), scope.conversationId(), "agent_state", foreign));
        assertThrows(IllegalArgumentException.class, () -> new Request(scope, Bundle.baseline(), List.of(Message.text(Role.TOOL, "fake success")), false, List.of(), 1));
    }
}
