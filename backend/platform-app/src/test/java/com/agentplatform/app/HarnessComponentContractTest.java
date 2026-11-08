package com.agentplatform.app;

import com.agentplatform.app.execution.harness.HarnessCompositionRoot;
import com.agentplatform.domain.harness.HarnessPorts.*;
import com.agentplatform.infrastructure.harness.agentscope.AgentScopeRuntimeFactory;
import com.agentplatform.types.harness.HarnessData.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class HarnessComponentContractTest {
    @TempDir Path root;
    private Scope scope() { return new Scope("0199db83-0000-7000-8000-000000000001", "0199db83-0000-7000-8000-000000000002", "0199db83-0000-7000-8000-000000000003", "0199db83-0000-7000-8000-000000000004", "0199db83-0000-7000-8000-000000000005", 1, false); }
    private static final LedgerPort LEDGER = new LedgerPort() {
        public CompletionStage<Void> commitRound(Scope s, Turn t) { return CompletableFuture.completedFuture(null); }
        public CompletionStage<Void> beforeTool(Scope s, Call c) { return CompletableFuture.failedFuture(new AssertionError("No tools allowed")); }
        public CompletionStage<Void> commitResult(Scope s, Call c, Observation o) { return CompletableFuture.failedFuture(new AssertionError("No tools allowed")); }
    };
    private static final class Slots implements StateStorage, RuntimeStatePort {
        private final Map<String, StateSlot> values = new HashMap<>();
        public synchronized StateSlot load(Scope s, String k) { return values.getOrDefault(k, new StateSlot(0, null)); }
        public synchronized long compareAndSwap(Scope s, String k, long e, byte[] b) { StateSlot old = load(s, k); if (e >= 0 && e != old.version()) return -1; values.put(k, new StateSlot(old.version() + 1, b)); return old.version() + 1; }
        public synchronized Set<String> keys(Scope s) { return Set.copyOf(values.keySet()); }
        public synchronized void delete(Scope s, String k) { values.remove(k); }
        public void verifyRestore(Scope s, Bundle b) { throw new IllegalStateException("No recovery fixture"); }
        public void seal(Scope s, Bundle b) {}
    }
    @Test void swapThreeFakeComponentsWithoutChangingRealLoopDriver() throws Exception {
        for (int implementation : List.of(1, 2)) {
            var captured = new AtomicReference<List<Message>>(); Slots state = new Slots();
            ModelGateway gateway = (s, messages, definitions) -> { captured.set(messages); return CompletableFuture.completedFuture(new Round("answer", List.of(), "stop", 2, 1)); };
            ContextAssembler context = implementation == 1
                    ? (s, history, candidates) -> { var all = new ArrayList<>(history); all.addAll(candidates); return CompletableFuture.completedFuture(List.copyOf(all)); }
                    : (s, history, candidates) -> CompletableFuture.completedFuture(java.util.stream.Stream.concat(history.stream(), candidates.stream()).toList());
            MemoryPort memory = s -> CompletableFuture.completedFuture(new EvidenceResult(List.of(reference("authorized memory")),false,null));
            RetrievalPort retrieval = (s,q) -> CompletableFuture.completedFuture(new EvidenceResult(List.of(reference("authorized evidence")),false,null));
            LoopBoundary boundary = new HarnessCompositionRoot(context, memory, retrieval, (s, candidate) -> CompletableFuture.completedFuture(Completion.PASS), LEDGER);
            LoopDriver driver = new AgentScopeRuntimeFactory(gateway, (s, c) -> Decision.DENY, (s, c) -> CompletableFuture.failedFuture(new AssertionError()), state, state, List.of(), root.resolve("impl" + implementation));
            try (var run = driver.prepare(new Request(scope(), Bundle.baseline(), List.of(Message.text(Role.USER, "question")), false, List.of(), 1), boundary)) {
                assertEquals(Completion.PASS, run.start().toCompletableFuture().get(10, TimeUnit.SECONDS).completion());
            }
            assertEquals(1, captured.get().stream().filter(message -> message.text().endsWith("authorized memory")).count());
            assertEquals(1, captured.get().stream().filter(message -> message.text().endsWith("authorized evidence")).count());
            assertTrue(captured.get().stream().filter(message -> message.text().startsWith("[PLATFORM_REFERENCE ")).allMatch(message -> message.role() == Role.ASSISTANT));
            assertTrue(captured.get().stream().noneMatch(message -> message.text().contains("secret") || message.text().contains("UI_ONLY")));
        }
    }
    @Test void cancellationClosesBothConcurrentRecallFutures() {
        var memory = new CompletableFuture<EvidenceResult>(); var retrieval = new CompletableFuture<EvidenceResult>();
        var boundary = new HarnessCompositionRoot((s, h, c) -> CompletableFuture.completedFuture(h), s -> memory, (s,q) -> retrieval,
                (s, c) -> CompletableFuture.completedFuture(Completion.INCOMPLETE), LEDGER);
        var projection = boundary.beforeModel(scope(), List.of()).toCompletableFuture();
        boundary.cancelPending(scope());
        assertTrue(memory.isCancelled()); assertTrue(retrieval.isCancelled()); assertTrue(projection.isCompletedExceptionally());
        boundary.release(scope());
    }
    @Test void missingEvidenceAndRepairAreNotSuccess() throws Exception {
        for (Completion decision : List.of(Completion.REPAIR, Completion.INCOMPLETE)) {
            Slots state = new Slots();
            var boundary = new HarnessCompositionRoot((s, h, c) -> CompletableFuture.completedFuture(h), s -> CompletableFuture.completedFuture(emptyEvidence()), (s,q) -> CompletableFuture.completedFuture(emptyEvidence()),
                    (s, c) -> CompletableFuture.completedFuture(decision), LEDGER);
            var requests = new java.util.concurrent.atomic.AtomicInteger();
            ModelGateway gateway = (s, messages, definitions) -> { requests.incrementAndGet(); return CompletableFuture.completedFuture(new Round("created imaginary file", List.of(), "stop", 1, 1)); };
            LoopDriver driver = new AgentScopeRuntimeFactory(gateway, (s, c) -> Decision.DENY, (s, c) -> CompletableFuture.failedFuture(new AssertionError()), state, state, List.of(), root);
            try (var run = driver.prepare(new Request(scope(), Bundle.baseline(), List.of(Message.text(Role.USER, "create file")), false, List.of(), 1), boundary)) { assertEquals(decision, run.start().toCompletableFuture().get(10, TimeUnit.SECONDS).completion()); }
            assertEquals(1, requests.get(), "No second outer Harness loop");
        }
    }
    @Test void typedReferencesCannotCarryToolFactsAndCancellationIsSpaceScoped() {
        assertThrows(IllegalArgumentException.class,()->new EvidenceReference("KNOWLEDGE","id","v1","invalid","loc","text",false));
        var first = new CompletableFuture<EvidenceResult>(); var second = new CompletableFuture<EvidenceResult>();
        Scope a = scope(); Scope b = new Scope("0199db83-0000-7000-8000-000000000099", a.actorId(), a.conversationId(), a.taskId(), a.attemptId(), 1, false);
        var isolated = new HarnessCompositionRoot((s, h, c) -> CompletableFuture.completedFuture(h), s -> s.spaceId().equals(a.spaceId()) ? first : second,
                (s,q) -> CompletableFuture.completedFuture(emptyEvidence()), (s, c) -> CompletableFuture.completedFuture(Completion.PASS), LEDGER);
        isolated.beforeModel(a, List.of()); isolated.beforeModel(b, List.of()); isolated.cancelPending(a);
        assertTrue(first.isCancelled()); assertFalse(second.isCancelled()); second.complete(emptyEvidence()); isolated.release(a); isolated.release(b);
    }
    private static EvidenceResult emptyEvidence(){return new EvidenceResult(List.of(),false,null);}
    private static EvidenceReference reference(String text){return new EvidenceReference("PERSONAL_MEMORY","memory-1","1","a".repeat(64),"PREFERENCE/topic",text,false);}
}
