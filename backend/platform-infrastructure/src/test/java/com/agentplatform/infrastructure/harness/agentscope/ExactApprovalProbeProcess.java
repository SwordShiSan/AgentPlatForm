package com.agentplatform.infrastructure.harness.agentscope;

import com.agentplatform.domain.harness.HarnessPorts.*;
import com.agentplatform.types.harness.HarnessData.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Actual pinned SDK experiment, one original invocation across separate JVMs. */
public final class ExactApprovalProbeProcess {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]); String mode = args[1]; boolean resume = !mode.equals("start");
        boolean allow = mode.equals("allow"); var store = new FileProbeStore(root);
        Scope source = FileProbeStore.scope();
        Scope scope = new Scope(source.spaceId(), source.actorId(), source.conversationId(), source.taskId(),
                resume ? "0199db83-0000-7000-8000-000000000006" : source.attemptId(), resume ? 2 : 1, false);
        Call original = FileProbeStore.call("exact-business-call");
        ModelGateway model = (s, history, definitions) -> {
            if (!resume) return CompletableFuture.completedFuture(new Round("", List.of(original), "tool_calls", 4, 2));
            if (history.stream().filter(m -> m.role() == Role.USER).count() != 1) throw new AssertionError("Approval became user input");
            var result = history.stream().flatMap(m -> m.observations().stream()).filter(o -> o.callId().equals(original.id())).toList();
            if (result.size() != 1 || result.getFirst().status() != (allow ? OperationStatus.SUCCEEDED : OperationStatus.DENIED))
                throw new AssertionError("Original tool result was not available before resumed reasoning: " + result);
            return CompletableFuture.completedFuture(new Round("continued after original tool result", List.of(), "stop", 4, 2));
        };
        LoopBoundary boundary = new LoopBoundary() {
            public CompletionStage<List<Message>> beforeModel(Scope s, List<Message> history) { return store.beforeModel(s, history); }
            public CompletionStage<Void> afterModelCommitted(Scope s, Turn turn) { return store.afterModelCommitted(s, turn); }
            public CompletionStage<Void> beforeTool(Scope s, Call call) {
                if (!allow) throw new AssertionError("DENY entered tool start");
                return store.beforeTool(s, call);
            }
            public CompletionStage<Void> afterToolCommitted(Scope s, Call call, Observation observation) {
                return store.afterToolCommitted(s, call, observation);
            }
            public CompletionStage<Completion> beforeStop(Scope s, Message candidate) { return CompletableFuture.completedFuture(Completion.PASS); }
        };
        var factory = new AgentScopeRuntimeFactory(model, (s, call) -> resume ? Decision.ALLOW : Decision.ASK,
                (s, call) -> { if (!allow) throw new AssertionError("DENY executed tool"); return store.execute(s, call); },
                store, store, FileProbeStore.definitions(), root.resolve("work"));
        var request = new Request(scope, Bundle.baseline(), resume ? List.of() : List.of(Message.text(Role.USER, "one exact operation")),
                resume, resume ? List.of(new Approval(original, allow)) : List.of(), resume ? 2 : 1);
        try (var run = factory.prepare(request, boundary)) {
            Outcome outcome = run.start().toCompletableFuture().get(15, TimeUnit.SECONDS);
            if (outcome.completion() != (resume ? Completion.PASS : Completion.WAITING_APPROVAL)) throw new AssertionError(outcome);
            run.terminated().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
        var journal = store.journal(scope);
        if (journal.effects() != (allow ? 1 : 0)) throw new AssertionError("Unexpected side-effect count");
        if (resume && journal.operations().get(original.id()).status() != (allow ? OperationStatus.SUCCEEDED : OperationStatus.DENIED))
            throw new AssertionError("Result not durably committed");
        Files.writeString(root.resolve(mode + ".done"), scope.attemptId());
    }
}
