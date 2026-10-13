package com.agentplatform.infrastructure.harness.agentscope;

import com.agentplatform.types.harness.HarnessData.*;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Separate JVM producer/consumer used by crash-window tests. */
public final class ProbeProcess {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]); var store = new FileProbeStore(root); Scope scope = FileProbeStore.scope();
        boolean restore = args[1].endsWith("restore");
        boolean approval = args[1].startsWith("ask") || args[1].startsWith("approve");
        store.ask = approval;
        if (args[1].startsWith("approve")) store.approved.addAll(List.of("call-1", "call-2"));
        var factory = new AgentScopeRuntimeFactory(store.model(), store.policy(), store, store, store, FileProbeStore.definitions(), root.resolve("workspace"));
        var approvals = args[1].startsWith("approve") ? List.of(new Approval(FileProbeStore.call("call-1"), true), new Approval(FileProbeStore.call("call-2"), true)) : List.<Approval>of();
        Request request = new Request(scope, Bundle.baseline(), restore ? List.of() : List.of(Message.text(Role.USER, "perform two probes")), restore, approvals, restore ? store.journal(scope).turns().size() + 1 : 1);
        try (var run = factory.prepare(request, store)) {
            Outcome outcome = run.start().toCompletableFuture().get(15, TimeUnit.SECONDS);
            if (outcome.completion() != Completion.PASS && !(args[1].equals("ask-start") && outcome.completion() == Completion.WAITING_APPROVAL)) throw new IllegalStateException("Incomplete fixture run");
            System.out.println("PROBE_PASS effects=" + store.journal(scope).effects() + " modelCalls=" + store.journal(scope).modelCalls());
        }
    }
}
