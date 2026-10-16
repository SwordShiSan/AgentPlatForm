package com.agentplatform.domain.harness;

import com.agentplatform.types.harness.HarnessData.*;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/** Explicit HC-1 ports. No Reactor, SDK, HTTP or persistence implementation types. */
public final class HarnessPorts {
    private HarnessPorts() {}

    public interface ModelGateway {
        CompletionStage<Round> complete(Scope scope, List<Message> messages, List<Call> toolDefinitions);
        /** Legacy complete-only adapters remain valid; production adapters deliver live deltas. */
        default CompletionStage<Round> stream(Scope scope, List<Message> messages, List<Call> toolDefinitions,
                                              java.util.function.Consumer<String> onText) {
            return complete(scope, messages, toolDefinitions);
        }
    }
    public interface ContextAssembler { CompletionStage<List<Message>> assemble(Scope scope, List<Message> history, List<Message> candidates); }
    public interface MemoryPort { CompletionStage<List<Message>> recall(Scope scope); }
    public interface RetrievalPort { CompletionStage<List<Message>> search(Scope scope); }
    public interface CompletionPolicy { CompletionStage<Completion> evaluate(Scope scope, Message candidate); }
    public interface PolicyPort { Decision decide(Scope scope, Call call); }
    public interface ToolExecutionPort { CompletionStage<Observation> execute(Scope scope, Call call); }
    public interface LedgerPort {
        CompletionStage<Void> commitRound(Scope scope, Turn turn);
        CompletionStage<Void> beforeTool(Scope scope, Call call);
        CompletionStage<Void> commitResult(Scope scope, Call call, Observation result);
    }
    public interface LoopBoundary {
        CompletionStage<List<Message>> beforeModel(Scope scope, List<Message> history);
        CompletionStage<Void> afterModelCommitted(Scope scope, Turn turn);
        CompletionStage<Void> beforeTool(Scope scope, Call call);
        CompletionStage<Void> afterToolCommitted(Scope scope, Call call, Observation result);
        CompletionStage<Completion> beforeStop(Scope scope, Message candidate);
        default void cancelPending(Scope scope) {}
        default void release(Scope scope) {}
    }
    public interface ToolCatalogPort { List<Call> definitions(); }
    public interface PreparedRun extends AutoCloseable {
        CompletionStage<Outcome> start();
        boolean cancel();
        boolean interrupt();
        @Override void close();
    }
    public interface LoopDriver { PreparedRun prepare(Request request, LoopBoundary boundary); }
    public interface RuntimeStatePort {
        void verifyRestore(Scope scope, Bundle bundle);
        void seal(Scope scope, Bundle bundle);
        default List<Message> recoveryHistory(Scope scope) { throw new IllegalStateException("CHECKPOINT_NOT_COMMITTED"); }
    }
    /** Opaque, atomically versioned private state; never a public message or domain fact. */
    public interface StateStorage {
        StateSlot load(Scope scope, String key);
        long compareAndSwap(Scope scope, String key, long expectedVersion, byte[] body);
        Set<String> keys(Scope scope);
        void delete(Scope scope, String key);
    }
}
