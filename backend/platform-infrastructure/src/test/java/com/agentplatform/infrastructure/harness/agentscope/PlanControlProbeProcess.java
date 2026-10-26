package com.agentplatform.infrastructure.harness.agentscope;

import com.agentplatform.domain.chat.PlanControls;
import com.agentplatform.domain.harness.HarnessPorts.*;
import com.agentplatform.types.harness.HarnessData.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Cross-JVM feasibility fixture, never a production scheduler or persistence implementation. */
public final class PlanControlProbeProcess {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]); boolean resume = args[1].equals("resume");
        var store = new FileProbeStore(root); Scope old = FileProbeStore.scope();
        Scope scope = new Scope(old.spaceId(), old.actorId(), old.conversationId(), old.taskId(),
                resume ? "0199db83-0000-7000-8000-000000000006" : old.attemptId(), resume ? 2 : 1, true);
        Call review = new Call("plan-review-1", "platform_plan_review", Map.of(), false);
        ModelGateway model = new ModelGateway() {
            public CompletionStage<Round> complete(Scope s, List<Message> messages, List<Call> ignored) { throw new AssertionError("Typed contract required"); }
            public CompletionStage<Round> streamTyped(Scope s, List<Message> messages, List<ToolDefinition> tools, java.util.function.Consumer<String> delta) {
                if (!tools.equals(PlanControls.DEFINITIONS)) throw new AssertionError("Wrong schemas");
                if (!resume) return CompletableFuture.completedFuture(new Round("", List.of(review), "tool_calls", 8, 3));
                if (messages.stream().filter(m -> m.role() == Role.USER).count() != 1) throw new AssertionError("Answer became USER input");
                if (messages.stream().flatMap(m -> m.observations().stream()).noneMatch(o -> o.text().contains("approved-revision-2")))
                    throw new AssertionError("Missing explicit control result");
                return CompletableFuture.completedFuture(new Round("Text continuation only", List.of(), "stop", 8, 3));
            }
        };
        ToolCatalogPort catalog = new ToolCatalogPort() {
            public List<Call> definitions() { return List.of(); }
            public List<ToolDefinition> typedDefinitions() { return PlanControls.DEFINITIONS; }
        };
        LoopBoundary boundary = new LoopBoundary() {
            public CompletionStage<List<Message>> beforeModel(Scope s, List<Message> h) { return store.beforeModel(s,h); }
            public CompletionStage<Void> afterModelCommitted(Scope s, Turn t) { return store.afterModelCommitted(s,t); }
            public CompletionStage<Void> beforeControlBatch(Scope s, List<Call> calls) {
                if (!calls.equals(List.of(review))) throw new AssertionError("Wrong pending call");
                if (store.journal(s).turns().isEmpty()) throw new AssertionError("Intent before full round");
                return CompletableFuture.completedFuture(null);
            }
            public CompletionStage<Void> beforeTool(Scope s, Call c) { return store.beforeTool(s,c); }
            public CompletionStage<Void> afterToolCommitted(Scope s, Call c, Observation o) { return store.afterToolCommitted(s,c,o); }
            public CompletionStage<Completion> beforeStop(Scope s, Message m) { return store.beforeStop(s,m); }
        };
        var factory = new AgentScopeRuntimeFactory(model, (s,c) -> resume ? Decision.ALLOW : Decision.ASK,
                (s,c) -> CompletableFuture.completedFuture(new Observation(c.id(),c.name(),"approved-revision-2; text only",OperationStatus.SUCCEEDED)),
                store, store, catalog, root.resolve("work"));
        var request = new Request(scope, Bundle.baseline(), resume ? List.of() : List.of(Message.text(Role.USER,"Plan this text")),
                resume, resume ? List.of(new Approval(review,true)) : List.of(), resume ? 2 : 1);
        try (var run = factory.prepare(request,boundary)) {
            Outcome outcome = run.start().toCompletableFuture().get(15,TimeUnit.SECONDS);
            if (outcome.completion() != (resume ? Completion.PASS : Completion.WAITING_INPUT)) throw new AssertionError(outcome);
            run.terminated().toCompletableFuture().get(5,TimeUnit.SECONDS);
            Files.writeString(root.resolve(resume ? "resumed.txt" : "stopped.txt"),scope.attemptId());
        }
    }
}
