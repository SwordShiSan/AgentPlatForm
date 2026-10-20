package com.agentplatform.infrastructure.harness.agentscope;

import com.agentplatform.domain.harness.HarnessPorts.*;
import com.agentplatform.types.harness.HarnessData.*;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.JacksonJsonCodec;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.*;

/** Actual pinned SDK experiment; no provider network or production recovery claim. */
class CheckpointRecoveryProbeTest {
    @TempDir Path root;

    @Test void frozenInputCanBeCopiedBeforeAnyCallAndRestoredInAnIndependentAttempt() throws Exception {
        Scope source = FileProbeStore.scope();
        Scope target = new Scope(source.spaceId(), source.actorId(), source.conversationId(), source.taskId(), UUID.randomUUID().toString(), 2, false);
        var first = new FileProbeStore(root.resolve("source"));
        var second = new FileProbeStore(root.resolve("target"));
        var calls = new AtomicInteger();
        var never = new ChatModelBase() {
            @Override public String getModelName() { return "never-called"; }
            @Override protected Flux<ChatResponse> doStream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                calls.incrementAndGet(); return Flux.error(new AssertionError("Snapshot must not call model"));
            }
        };
        var context = RuntimeContext.builder().userId(source.actorId()).sessionId(source.conversationId()).build();
        List<Message> frozen = List.of(Message.text(Role.SYSTEM, "frozen-system"), Message.text(Role.USER, "frozen-user😀"));
        byte[] encoded;
        try (var agent = HarnessAgent.builder().name("snapshot-experiment").model(never)
                .stateStore(new ScopeStateStore(first, source)).disableCompaction().disableMemoryHooks()
                .disableMemoryTools().disableFilesystemTools().disableShellTool().disableWebTools()
                .disableDynamicSkills().disableSubagents().disableDynamicSubagents().disableTranscript()
                .disableWorkspaceContext().disableDefaultWorkspaceSkills().disableAtPathExpansion().disableToolsConfig().build()) {
            var codec = new JacksonJsonCodec();
            var live = agent.getDelegate().getAgentState(context);
            var clone = codec.fromJson(codec.toJson(live), AgentState.class);
            clone.contextMutable().clear();
            clone.contextMutable().addAll(List.of(Msg.builder().role(MsgRole.SYSTEM).textContent(frozen.getFirst().text()).build(),
                    Msg.builder().role(MsgRole.USER).textContent(frozen.getLast().text()).build()));
            encoded = codec.toJson(clone).getBytes(StandardCharsets.UTF_8);
            assertTrue(live.getContext().isEmpty(), "A checkpoint clone must not mutate the active SDK cache");
        }
        assertEquals(0, calls.get());
        assertEquals(1, second.compareAndSwap(target, "agent_state", 0, encoded));
        assertEquals(0, first.load(source, "agent_state").version());
        ModelGateway model = (scope, messages, tools) -> {
            calls.incrementAndGet(); assertEquals(target, scope);
            assertEquals(frozen, messages, "No extra caller input or missing frozen context after restoration");
            return CompletableFuture.completedFuture(new Round("restored", List.of(), "stop", 4, 1));
        };
        RuntimeStatePort manifests = new RuntimeStatePort() {
            public void verifyRestore(Scope s, Bundle b) { assertEquals(target, s); assertEquals(Bundle.baseline(), b); }
            public List<Message> recoveryHistory(Scope s) { return frozen; }
            public void seal(Scope s, Bundle b) {}
        };
        LoopBoundary boundary = new LoopBoundary() {
            public CompletionStage<List<Message>> beforeModel(Scope s, List<Message> history) { assertEquals(frozen, history); return CompletableFuture.completedFuture(history); }
            public CompletionStage<Void> afterModelCommitted(Scope s, Turn t) { assertEquals(2, t.ordinal()); return CompletableFuture.completedFuture(null); }
            public CompletionStage<Void> beforeTool(Scope s, Call c) { return CompletableFuture.failedFuture(new AssertionError("No tools")); }
            public CompletionStage<Void> afterToolCommitted(Scope s, Call c, Observation o) { return CompletableFuture.failedFuture(new AssertionError("No tools")); }
            public CompletionStage<Completion> beforeStop(Scope s, Message m) { return CompletableFuture.completedFuture(Completion.PASS); }
        };
        var factory = new AgentScopeRuntimeFactory(model, (s,c)->Decision.DENY, (s,c)->CompletableFuture.failedFuture(new AssertionError()), second, manifests, List.of(), root.resolve("work"));
        try (var run = factory.prepare(new Request(target, Bundle.baseline(), List.of(), true, List.of(), 2), boundary)) {
            assertEquals(Completion.PASS, run.start().toCompletableFuture().get(12, TimeUnit.SECONDS).completion());
            run.terminated().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
        assertEquals(1, calls.get());
        assertThrows(IllegalArgumentException.class, () -> new Request(target, Bundle.baseline(), List.of(frozen.getLast()), true, List.of(), 2));
        assertEquals(0, first.load(source, "agent_state").version());
    }
}
