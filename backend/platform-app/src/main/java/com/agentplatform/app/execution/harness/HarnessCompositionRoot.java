package com.agentplatform.app.execution.harness;

import com.agentplatform.domain.harness.HarnessPorts.*;
import com.agentplatform.types.harness.HarnessData.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Set;

/** Coordinator callbacks, constructed explicitly. No background loops or SDK defaults. */
public final class HarnessCompositionRoot implements LoopBoundary {
    private final ContextAssembler context;
    private final MemoryPort memory;
    private final RetrievalPort retrieval;
    private final CompletionPolicy completion;
    private final LedgerPort ledger;
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<CompletableFuture<?>>> pending = new ConcurrentHashMap<>();
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();

    public HarnessCompositionRoot(ContextAssembler context, MemoryPort memory, RetrievalPort retrieval,
                                  CompletionPolicy completion, LedgerPort ledger) {
        this.context = java.util.Objects.requireNonNull(context);
        this.memory = java.util.Objects.requireNonNull(memory);
        this.retrieval = java.util.Objects.requireNonNull(retrieval);
        this.completion = java.util.Objects.requireNonNull(completion);
        this.ledger = java.util.Objects.requireNonNull(ledger);
    }
    @Override public CompletionStage<List<Message>> beforeModel(Scope scope, List<Message> history) {
        String query=history.stream().filter(message->message.role()==Role.USER).reduce((left,right)->right).map(Message::text).orElse("");
        return track(scope, memory.recall(scope)).thenCombine(track(scope, retrieval.search(scope,query)), (memories, evidence) -> {
            var candidates = new ArrayList<Message>();
            memories.references().forEach(reference -> candidates.add(reference(reference)));
            evidence.references().forEach(reference -> candidates.add(reference(reference)));
            return List.copyOf(candidates);
        }).thenCompose(candidates -> track(scope, context.assemble(scope, List.copyOf(history), candidates)));
    }
    @Override public CompletionStage<Void> afterModelCommitted(Scope scope, Turn turn) { return ledger.commitRound(scope, turn); }
    @Override public CompletionStage<Void> beforeTool(Scope scope, Call call) { return ledger.beforeTool(scope, call); }
    @Override public CompletionStage<Void> afterToolCommitted(Scope scope, Call call, Observation result) { return ledger.commitResult(scope, call, result); }
    @Override public CompletionStage<Completion> beforeStop(Scope scope, Message candidate) { return completion.evaluate(scope, candidate); }
    private <T> CompletableFuture<T> track(Scope scope, CompletionStage<T> stage) {
        var future = stage.toCompletableFuture();
        pending.computeIfAbsent(key(scope), key -> new CopyOnWriteArrayList<>()).add(future);
        if (cancelled.contains(key(scope))) future.cancel(true);
        return future;
    }
    @Override public void cancelPending(Scope scope) {
        cancelled.add(key(scope));
        pending.getOrDefault(key(scope), new CopyOnWriteArrayList<>()).forEach(future -> future.cancel(true));
    }
    @Override public void release(Scope scope) { pending.remove(key(scope)); cancelled.remove(key(scope)); }
    private String key(Scope scope) { return scope.spaceId() + ":" + scope.attemptId(); }
    private Message reference(EvidenceReference source) {
        // References carry provenance and untrusted text only; they cannot forge tool observations or SDK state.
        String value="[PLATFORM_REFERENCE untrusted=true kind="+source.sourceKind()+" id="+source.sourceId()+" version="+source.sourceVersion()
                +" sha256="+source.contentSha256()+" location="+source.location()+"]\n"+source.text();
        return Message.text(Role.ASSISTANT,value);
    }
}
