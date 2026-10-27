package com.agentplatform.domain.harness;

import com.agentplatform.types.harness.HarnessData.*;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/** Explicit HC-1 ports. No Reactor, SDK, HTTP or persistence implementation types. */
public final class HarnessPorts {
    private HarnessPorts() {}

    public interface ModelGateway {
        /** Actual frozen provider model identity; standalone scripted probes keep their default. */
        default String modelName(Scope scope) { return "scripted"; }
        CompletionStage<Round> complete(Scope scope, List<Message> messages, List<Call> toolDefinitions);
        /** Legacy complete-only adapters remain valid; production adapters deliver live deltas. */
        default CompletionStage<Round> stream(Scope scope, List<Message> messages, List<Call> toolDefinitions,
                                              java.util.function.Consumer<String> onText) {
            return complete(scope, messages, toolDefinitions);
        }
        default CompletionStage<Round> streamTyped(Scope scope, List<Message> messages, List<ToolDefinition> definitions,
                                                   java.util.function.Consumer<String> onText) {
            if (definitions.stream().anyMatch(definition -> definition.legacy() == null))
                return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("TYPED_TOOLS_NOT_SUPPORTED"));
            return stream(scope, messages, definitions.stream().map(ToolDefinition::legacy).toList(), onText);
        }
    }
    public interface ContextAssembler { CompletionStage<List<Message>> assemble(Scope scope, List<Message> history, List<Message> candidates); }
    public interface MemoryPort { CompletionStage<List<Message>> recall(Scope scope); }
    public interface RetrievalPort { CompletionStage<List<Message>> search(Scope scope); }
    public interface CompletionPolicy { CompletionStage<Completion> evaluate(Scope scope, Message candidate); }
    public interface PolicyPort { Decision decide(Scope scope, Call call); }
    /** Physical termination is independent of result delivery or cancellation of a Java Future. */
    public interface ToolExchange {
        CompletionStage<Observation> result();
        CompletionStage<Void> terminated();
        void cancel();
    }
    public interface ToolExecutionPort {
        CompletionStage<Observation> execute(Scope scope, Call call);
        /** Legacy probes have no remote termination proof and cannot be used as production exchanges. */
        default ToolExchange open(Scope scope, Call call) {
            throw new IllegalStateException("TOOL_TERMINATION_NOT_SUPPORTED");
        }
    }
    public interface LedgerPort {
        CompletionStage<Void> commitRound(Scope scope, Turn turn);
        CompletionStage<Void> beforeTool(Scope scope, Call call);
        CompletionStage<Void> commitResult(Scope scope, Call call, Observation result);
    }
    public interface LoopBoundary {
        CompletionStage<List<Message>> beforeModel(Scope scope, List<Message> history);
        CompletionStage<Void> afterModelCommitted(Scope scope, Turn turn);
        /** Durable complete-call intent boundary, after the full round and before SDK permission/ASK. */
        default CompletionStage<Void> beforeControlBatch(Scope scope, List<Call> calls) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        default CompletionStage<Void> beforeWaiting(Scope scope,List<Call> calls){return java.util.concurrent.CompletableFuture.completedFuture(null);}
        CompletionStage<Void> beforeTool(Scope scope, Call call);
        /** Persist either executed SUCCEEDED or SDK DENIED; DENIED never enters beforeTool. */
        CompletionStage<Void> afterToolCommitted(Scope scope, Call call, Observation result);
        CompletionStage<Completion> beforeStop(Scope scope, Message candidate);
        default void cancelPending(Scope scope) {}
        default void release(Scope scope) {}
    }
    public interface ToolCatalogPort {
        List<Call> definitions();
        default List<ToolDefinition> typedDefinitions() { return definitions().stream().map(ToolDefinition::legacy).toList(); }
    }
    public interface PreparedRun extends AutoCloseable {
        CompletionStage<Outcome> start();
        default CompletionStage<Void> terminated() {
            return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("TERMINATION_NOT_CONFIRMED"));
        }
        boolean cancel();
        boolean interrupt();
        @Override void close();
    }
    public interface LoopDriver { PreparedRun prepare(Request request, LoopBoundary boundary); }
    public interface RuntimeStatePort {
        void verifyRestore(Scope scope, Bundle bundle);
        void seal(Scope scope, Bundle bundle);
        /** Canonical history for immutable safe-point snapshots; empty preserves legacy adapters. */
        default List<Message> checkpointHistory(Scope scope) { return List.of(); }
        default List<Message> recoveryHistory(Scope scope) { throw new IllegalStateException("CHECKPOINT_NOT_COMMITTED"); }
        /** Explicit production capability; legacy probes retain their original save/seal contract. */
        default boolean atomicCheckpoints(Scope scope) { return false; }
        default void commitCheckpoint(Scope scope, Bundle bundle, CheckpointDraft draft) {
            throw new IllegalStateException("ATOMIC_CHECKPOINT_NOT_SUPPORTED");
        }
    }
    /** Opaque, atomically versioned private state; never a public message or domain fact. */
    public interface StateStorage {
        StateSlot load(Scope scope, String key);
        long compareAndSwap(Scope scope, String key, long expectedVersion, byte[] body);
        Set<String> keys(Scope scope);
        void delete(Scope scope, String key);
    }
}
