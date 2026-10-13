package com.agentplatform.infrastructure.harness.agentscope;

import com.agentplatform.domain.harness.CompositionRules;
import com.agentplatform.domain.harness.HarnessPorts.*;
import com.agentplatform.types.harness.HarnessData.*;
import io.agentscope.core.state.State;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class AgentScopeContractProbeTest {
    @TempDir Path root;
    public record SampleState(String value) implements State {}
    private AgentScopeRuntimeFactory factory(FileProbeStore store, ModelGateway model) {
        return new AgentScopeRuntimeFactory(model, store.policy(), store, store, store, FileProbeStore.definitions(), root.resolve("workspace"));
    }
    private Request request(Scope scope) { return new Request(scope, Bundle.baseline(), List.of(Message.text(Role.USER, "perform two probes")), false, List.of(), 1); }
    private Outcome finish(PreparedRun run) throws Exception { return run.start().toCompletableFuture().get(12, TimeUnit.SECONDS); }

    @Test void realHarnessCommitsFullBatchBeforeTwoToolsAndFinalReply() throws Exception {
        var store = new FileProbeStore(root);
        try (var run = factory(store, store.model()).prepare(request(FileProbeStore.scope()), store)) {
            assertEquals(Completion.PASS, finish(run).completion());
        }
        var ledger = store.journal(FileProbeStore.scope());
        assertEquals(2, ledger.effects()); assertEquals(2, ledger.modelCalls());
        assertEquals(List.of("call-1", "call-2"), ledger.turns().getFirst().round().calls().stream().map(Call::id).toList());
        assertTrue(ledger.operations().values().stream().allMatch(op -> op.status() == OperationStatus.SUCCEEDED));
        store.verifyRestore(FileProbeStore.scope(), Bundle.baseline());
    }
    @Test void delayedRoundCannotStartAnyTool() throws Exception {
        var store = new FileProbeStore(root); store.delayedRound = new CompletableFuture<>();
        try (var run = factory(store, store.model()).prepare(request(FileProbeStore.scope()), store)) {
            var result = run.start().toCompletableFuture();
            assertFalse(result.isDone()); assertEquals(0, store.journal(FileProbeStore.scope()).effects());
            store.delayedRound.complete(null); assertEquals(Completion.PASS, result.get(12, TimeUnit.SECONDS).completion());
        }
    }
    @Test void roundCommitFailureHasZeroEffect() throws Exception {
        var store = new FileProbeStore(root); store.failRound = true;
        try (var run = factory(store, store.model()).prepare(request(FileProbeStore.scope()), store)) { assertThrows(ExecutionException.class, () -> finish(run)); }
        assertEquals(0, store.journal(FileProbeStore.scope()).effects());
    }
    @Test void resultCommitFailureStopsNextToolAndMarksUncertainty() throws Exception {
        var store = new FileProbeStore(root); store.failResult = true;
        try (var run = factory(store, store.model()).prepare(request(FileProbeStore.scope()), store)) { assertThrows(ExecutionException.class, () -> finish(run)); }
        assertEquals(1, store.journal(FileProbeStore.scope()).effects());
        assertTrue(store.journal(FileProbeStore.scope()).operations().values().stream().anyMatch(op -> op.status() == OperationStatus.RUNNING));
    }
    @ParameterizedTest @ValueSource(strings = {"length", "abort", "content_filter", "unknown"})
    void truncatedOrUnsupportedFinishNeverRunsTool(String reason) {
        var store = new FileProbeStore(root);
        ModelGateway model = (scope, messages, defs) -> CompletableFuture.completedFuture(new Round("", List.of(FileProbeStore.call("bad-call")), reason, 0, 0));
        try (var run = factory(store, model).prepare(request(FileProbeStore.scope()), store)) { assertThrows(ExecutionException.class, () -> finish(run)); }
        assertEquals(0, store.journal(FileProbeStore.scope()).effects());
    }
    @Test void emptyAndMalformedOutputAreNotSuccess() {
        var store = new FileProbeStore(root);
        for (Round round : List.of(new Round("", List.of(), "stop", 0, 0), new Round("", List.of(new Call("bad", "write_probe", Map.of("wrong", "x"), false)), "tool_calls", 0, 0))) {
            ModelGateway model = (scope, messages, defs) -> CompletableFuture.completedFuture(round);
            try (var run = factory(store, model).prepare(request(FileProbeStore.scope()), store)) { assertThrows(ExecutionException.class, () -> finish(run)); }
        }
        assertEquals(0, store.journal(FileProbeStore.scope()).effects());
    }
    @Test void planAndRevocationDenyWrites() throws Exception {
        for (boolean plan : List.of(true, false)) {
            Path fixture = root.resolve("mode-" + plan); var store = new FileProbeStore(fixture); store.revoked = !plan;
            Scope base = FileProbeStore.scope(); Scope scope = new Scope(base.spaceId(), base.actorId(), base.conversationId(), base.taskId(), base.attemptId(), 1, plan);
            try (var run = factory(store, store.model()).prepare(request(scope), store)) {
                assertNotEquals(Completion.PASS, finish(run).completion());
            }
            assertEquals(0, store.journal(scope).effects());
        }
    }
    @Test void approvalSurvivesNewHarnessWithoutUserControlText() throws Exception {
        var store = new FileProbeStore(root); store.ask = true; Scope scope = FileProbeStore.scope();
        try (var first = factory(store, store.model()).prepare(request(scope), store)) { assertEquals(Completion.WAITING_APPROVAL, finish(first).completion()); }
        assertEquals(0, store.journal(scope).effects());
        store.approved.addAll(List.of("call-1", "call-2"));
        var resumed = new Request(scope, Bundle.baseline(), List.of(), true, List.of(new Approval(FileProbeStore.call("call-1"), true), new Approval(FileProbeStore.call("call-2"), true)), 2);
        try (var second = factory(store, store.model()).prepare(resumed, store)) { assertEquals(Completion.PASS, finish(second).completion()); }
        assertEquals(2, store.journal(scope).effects());
        var privateState = new ScopeStateStore(store, scope).get(scope.actorId(), scope.conversationId(), "agent_state", io.agentscope.core.state.AgentState.class).orElseThrow();
        assertEquals(1, privateState.getContext().stream().filter(message -> message.getRole() == io.agentscope.core.message.MsgRole.USER).count());
        assertTrue(privateState.getContext().stream().noneMatch(message -> message.getMetadata().containsKey(io.agentscope.core.message.Msg.METADATA_CONFIRM_RESULTS)));
    }
    @Test void cancelBeforeSubscriptionAndDuringModelDoesNotExecute() throws Exception {
        var store = new FileProbeStore(root); var modelFuture = new CompletableFuture<Round>(); var calls = new AtomicInteger();
        ModelGateway model = (scope, messages, defs) -> { calls.incrementAndGet(); return modelFuture; };
        try (var run = factory(store, model).prepare(request(FileProbeStore.scope()), store)) {
            assertTrue(run.cancel()); assertEquals(Completion.CANCELLED, finish(run).completion()); assertEquals(0, calls.get());
        }
        try (var run = factory(store, model).prepare(request(FileProbeStore.scope()), store)) {
            var result = run.start().toCompletableFuture(); assertEquals(1, calls.get());
            run.cancel(); assertEquals(Completion.CANCELLED, result.get(5, TimeUnit.SECONDS).completion());
        }
        assertEquals(0, store.journal(FileProbeStore.scope()).effects());
    }
    @Test void casJsonAndScopeIsolation() throws Exception {
        var store = new FileProbeStore(root); Scope scope = FileProbeStore.scope(); var sdk = new ScopeStateStore(store, scope);
        assertTrue(sdk.supportsVersioning()); assertEquals(0, sdk.getVersioned(scope.actorId(), scope.conversationId(), "test", SampleState.class).version());
        assertEquals(1, sdk.saveIfVersion(scope.actorId(), scope.conversationId(), "test", new SampleState("value"), 0));
        assertEquals("value", sdk.get(scope.actorId(), scope.conversationId(), "test", SampleState.class).orElseThrow().value());
        assertThrows(IllegalStateException.class, () -> sdk.saveIfVersion(scope.actorId(), scope.conversationId(), "test", new SampleState("bad"), 0));
        Scope other = new Scope("0199db83-0000-7000-8000-000000000009", scope.actorId(), scope.conversationId(), scope.taskId(), scope.attemptId(), 1, false);
        assertTrue(new ScopeStateStore(store, other).get(scope.actorId(), scope.conversationId(), "test", SampleState.class).isEmpty());
        assertThrows(SecurityException.class, () -> sdk.listSessionIds(other.spaceId()));
        sdk.save(scope.actorId(), scope.conversationId(), "list", List.of(new SampleState("one"), new SampleState("two")));
        assertEquals(2, sdk.getList(scope.actorId(), scope.conversationId(), "list", SampleState.class).size());
        sdk.delete(scope.actorId(), scope.conversationId(), "test"); assertTrue(sdk.get(scope.actorId(), scope.conversationId(), "test", SampleState.class).isEmpty());
    }
    @ParameterizedTest @ValueSource(strings = {"AFTER_ROUND", "BEFORE_TOOL", "AFTER_TOOL_EFFECT", "AFTER_TOOL_COMMIT"})
    void realJvmCrashRecoveryPreservesIdsAndDoesNotReinfer(String crash) throws Exception {
        Path fixture = root.resolve(crash); int crashed = process(fixture, "start", crash); assertEquals(73, crashed);
        var store = new FileProbeStore(fixture); Scope scope = FileProbeStore.scope();
        assertEquals(1, store.journal(scope).modelCalls());
        int restored = process(fixture, "restore", "");
        if (crash.equals("AFTER_TOOL_EFFECT")) { assertNotEquals(0, restored); assertEquals(1, store.journal(scope).effects()); assertEquals(1, store.journal(scope).modelCalls()); }
        else { assertEquals(0, restored); assertEquals(2, store.journal(scope).effects()); assertEquals(2, store.journal(scope).modelCalls()); }
        assertEquals(Set.of("call-1", "call-2"), store.journal(scope).operations().keySet());
    }
    private int process(Path fixture, String mode, String crash) throws Exception {
        Files.createDirectories(fixture);
        Path log = fixture.resolve(mode + ".log");
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        Process child = new ProcessBuilder(java, "-Xmx160m", "-Dprobe.crash=" + crash, "-cp", System.getProperty("java.class.path"), ProbeProcess.class.getName(), fixture.toString(), mode)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!child.waitFor(30, TimeUnit.SECONDS)) { child.destroyForcibly(); fail("Probe subprocess timed out: " + log); }
        if (child.exitValue() != 0 && child.exitValue() != 73) System.out.println(Files.readString(log));
        return child.exitValue();
    }
    @Test void approvalRestoresAcrossActualJvmExit() throws Exception {
        Path fixture = root.resolve("approval-jvm"); assertEquals(0, process(fixture, "ask-start", ""));
        var store = new FileProbeStore(fixture); Scope scope = FileProbeStore.scope();
        assertEquals(1, store.journal(scope).modelCalls()); assertEquals(0, store.journal(scope).effects());
        assertEquals(0, process(fixture, "approve-restore", ""));
        assertEquals(2, store.journal(scope).effects()); assertEquals(2, store.journal(scope).modelCalls());
    }
    @Test void modifiedOrDuplicateApprovalCannotExecute() throws Exception {
        for (boolean duplicate : List.of(false, true)) {
            var store = new FileProbeStore(root.resolve("approval-" + duplicate)); store.ask = true; Scope scope = FileProbeStore.scope();
            try (var first = factory(store, store.model()).prepare(request(scope), store)) { assertEquals(Completion.WAITING_APPROVAL, finish(first).completion()); }
            store.approved.addAll(List.of("call-1", "call-2"));
            Call edited = new Call("call-1", "write_probe", Map.of("value", "edited"), false);
            var approvals = duplicate ? List.of(new Approval(FileProbeStore.call("call-1"), true), new Approval(FileProbeStore.call("call-1"), true))
                    : List.of(new Approval(edited, true), new Approval(FileProbeStore.call("call-2"), true));
            var resumed = new Request(scope, Bundle.baseline(), List.of(), true, approvals, 2);
            try (var run = factory(store, store.model()).prepare(resumed, store)) { assertThrows(ExecutionException.class, () -> finish(run)); }
            assertEquals(0, store.journal(scope).effects());
        }
    }
    @Test void invalidBundleAndDuplicateAuthoritiesFailClosed() {
        Bundle base = Bundle.baseline();
        assertThrows(IllegalArgumentException.class, () -> CompositionRules.validate(base, 2, 1, false));
        assertThrows(IllegalArgumentException.class, () -> CompositionRules.validate(base, 1, 2, false));
        assertThrows(IllegalArgumentException.class, () -> CompositionRules.validate(base, 1, 1, true));
        assertThrows(IllegalArgumentException.class, () -> CompositionRules.validate(new Bundle("2.0.4", 1, 2, "platform-v1", "scripted", base.components()), 1, 1, false));
        assertThrows(IllegalArgumentException.class, () -> CompositionRules.validate(new Bundle("2.0.4", 1, 1, "platform-v2", "other", base.components()), 1, 1, false));
        var store = new FileProbeStore(root); assertThrows(IllegalStateException.class, () -> store.verifyRestore(FileProbeStore.scope(), base));
    }
}
