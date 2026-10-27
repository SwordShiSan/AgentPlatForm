package com.agentplatform.infrastructure.harness.agentscope;

import com.agentplatform.domain.chat.PlanControls;
import com.agentplatform.domain.harness.HarnessPorts.*;
import com.agentplatform.types.harness.HarnessData.*;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.message.*;
import io.agentscope.core.util.JacksonJsonCodec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ApprovalCheckpointProbeTest {
    @TempDir Path root;
    @Test void productionApprovalSnapshotPreservesAskingOriginalCallAfterFullRoundCommit() throws Exception {
        var probe = new Probe(List.of(FileProbeStore.call("one")));
        try (var run = probe.factory().prepare(probe.request(), probe.boundary())) {
            assertEquals(Completion.WAITING_APPROVAL, run.start().toCompletableFuture().get(15, TimeUnit.SECONDS).completion());
            run.terminated().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
        assertEquals(0, probe.store.journal(probe.scope).effects());
        assertEquals(1, probe.store.journal(probe.scope).turns().size());
        var snapshot = probe.drafts.getLast();
        assertEquals(CheckpointPhase.WAITING_APPROVAL, snapshot.phase());
        var sdk = new JacksonJsonCodec().fromJson(new String(snapshot.state(), StandardCharsets.UTF_8), AgentState.class);
        var calls = sdk.getContext().stream().flatMap(m -> m.getContentBlocks(ToolUseBlock.class).stream()).toList();
        assertEquals(1, calls.size()); assertEquals("one", calls.getFirst().getId()); assertEquals(ToolCallState.ASKING, calls.getFirst().getState());
        assertEquals(1, probe.stages.get());
    }
    @Test void productionMultipleBusinessCallsAreAllCommittedThenRejectedBeforeAnyActing() {
        assertUnsupported(List.of(FileProbeStore.call("one"), FileProbeStore.call("two")));
    }
    @Test void productionBusinessAndControlMixIsRejectedBeforeAnyActing() {
        assertUnsupported(List.of(FileProbeStore.call("one"), new Call("review", "platform_plan_review", Map.of(), false)));
    }
    private void assertUnsupported(List<Call> calls) {
        var probe = new Probe(calls);
        try (var run = probe.factory().prepare(probe.request(), probe.boundary())) {
            var error = assertThrows(ExecutionException.class, () -> run.start().toCompletableFuture().get(15, TimeUnit.SECONDS));
            assertEquals("TOOL_BATCH_UNSUPPORTED", error.getCause().getMessage());
        }
        var journal = probe.store.journal(probe.scope);
        assertEquals(1, journal.turns().size()); assertEquals(calls, journal.turns().getFirst().round().calls());
        assertEquals(calls.size(), journal.operations().size()); assertEquals(0, journal.effects()); assertEquals(0, probe.stages.get());
        assertTrue(journal.operations().values().stream().allMatch(o -> o.status() == OperationStatus.PREPARED));
    }
    private final class Probe {
        final Scope scope = FileProbeStore.scope();
        final FileProbeStore store = new FileProbeStore(root.resolve(UUID.randomUUID().toString()));
        final List<Call> calls; final List<CheckpointDraft> drafts = new ArrayList<>(); final AtomicInteger stages = new AtomicInteger();
        final Message input = Message.text(Role.USER, "one controlled operation");
        Probe(List<Call> calls) { this.calls = calls; store.beforeModel(scope, List.of(input)); }
        Request request() { return new Request(scope, Bundle.baseline(), List.of(input), false, List.of(), 1); }
        AgentScopeRuntimeFactory factory() {
            ModelGateway model = new ModelGateway() {
                public CompletionStage<Round> complete(Scope s, List<Message> m, List<Call> t) { throw new AssertionError("Typed model required"); }
                public CompletionStage<Round> streamTyped(Scope s, List<Message> m, List<ToolDefinition> t, java.util.function.Consumer<String> delta) {
                    return CompletableFuture.completedFuture(new Round("", calls, "tool_calls", 4, 2));
                }
            };
            ToolCatalogPort catalog = new ToolCatalogPort() {
                public List<Call> definitions() { return FileProbeStore.definitions(); }
                public List<ToolDefinition> typedDefinitions() {
                    var definitions = new ArrayList<ToolDefinition>(); definitions.add(ToolDefinition.legacy(FileProbeStore.definitions().getFirst()));
                    definitions.addAll(PlanControls.DEFINITIONS); return List.copyOf(definitions);
                }
            };
            RuntimeStatePort snapshots = new RuntimeStatePort() {
                public void verifyRestore(Scope s, Bundle b) { throw new AssertionError("Not a restore"); }
                public void seal(Scope s, Bundle b) { throw new AssertionError("Atomic checkpoints required"); }
                public boolean atomicCheckpoints(Scope s) { return true; }
                public List<Message> checkpointHistory(Scope s) { return store.recoveryHistory(s); }
                public void commitCheckpoint(Scope s, Bundle b, CheckpointDraft draft) {
                    assertEquals(draft.expectedVersion() + 1, store.compareAndSwap(s, "checkpoint_agent_state", draft.expectedVersion(), draft.state())); drafts.add(draft);
                }
            };
            return new AgentScopeRuntimeFactory(model, (s, c) -> Decision.ASK, store, store, snapshots, catalog, root.resolve("work"));
        }
        LoopBoundary boundary() {
            return new LoopBoundary() {
                public CompletionStage<List<Message>> beforeModel(Scope s, List<Message> h) { return store.beforeModel(s, h); }
                public CompletionStage<Void> afterModelCommitted(Scope s, Turn t) { return store.afterModelCommitted(s, t); }
                public CompletionStage<Void> beforeControlBatch(Scope s, List<Call> c) { stages.incrementAndGet(); return CompletableFuture.completedFuture(null); }
                public CompletionStage<Void> beforeTool(Scope s, Call c) { return store.beforeTool(s, c); }
                public CompletionStage<Void> afterToolCommitted(Scope s, Call c, Observation o) { return store.afterToolCommitted(s, c, o); }
                public CompletionStage<Completion> beforeStop(Scope s, Message m) { return CompletableFuture.completedFuture(Completion.PASS); }
            };
        }
    }
}
