package com.agentplatform.infrastructure.harness.agentscope;

import com.agentplatform.domain.harness.HarnessPorts.*;
import com.agentplatform.types.harness.HarnessData.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/** Deterministic, durable probe fixture ONLY. Not a production PG or task scheduler adapter. */
public final class FileProbeStore implements StateStorage, RuntimeStatePort, LedgerPort, ToolExecutionPort, LoopBoundary {
    public record Operation(Call call, OperationStatus status, Observation result) {}
    public record Journal(List<Message> prefix, List<Turn> turns, Map<String, Operation> operations, int effects, int modelCalls) {}
    public record Slots(Map<String, StateSlot> values) {}
    public record Manifest(String bundleHash, int schema, long watermark, Map<String, Long> versions, Map<String, String> hashes) {}
    private static final Map<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
    private final Path root;
    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
    public volatile boolean revoked;
    public volatile boolean ask;
    public volatile boolean failRound;
    public volatile boolean failResult;
    public volatile CompletableFuture<Void> delayedRound;
    public final Set<String> approved = ConcurrentHashMap.newKeySet();

    public FileProbeStore(Path root) { this.root = root.toAbsolutePath().normalize(); }
    public static Scope scope() {
        return new Scope("0199db83-0000-7000-8000-000000000001", "0199db83-0000-7000-8000-000000000002",
                "0199db83-0000-7000-8000-000000000003", "0199db83-0000-7000-8000-000000000004",
                "0199db83-0000-7000-8000-000000000005", 1, false);
    }
    public static List<Call> definitions() { return List.of(new Call("definition", "write_probe", Map.of("value", ""), false)); }
    public static Call call(String id) { return new Call(id, "write_probe", Map.of("value", id), false); }
    private Path task(Scope scope, String name) { return root.resolve(scope.spaceId()).resolve(scope.taskId()).resolve(name); }
    private Path states(Scope scope) { return root.resolve(scope.spaceId()).resolve(scope.actorId()).resolve(scope.conversationId()).resolve("sdk.json"); }
    private static Journal empty() { return new Journal(List.of(), List.of(), new LinkedHashMap<>(), 0, 0); }

    private <T> T locked(Path file, java.util.function.Supplier<T> action) {
        ReentrantLock lock = LOCKS.computeIfAbsent(file, key -> new ReentrantLock());
        lock.lock();
        try {
            Files.createDirectories(file.getParent());
            try (FileChannel channel = FileChannel.open(file.resolveSibling(file.getFileName() + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var ignored = channel.lock()) { return action.get(); }
        } catch (IOException error) { throw new IllegalStateException("PROBE_IO_FAILED", error); }
        finally { lock.unlock(); }
    }
    private <T> T read(Path file, Class<T> type, T missing) {
        if (!Files.exists(file)) return missing;
        try { return json.readValue(Files.readAllBytes(file), type); }
        catch (Exception error) { throw new IllegalStateException("CHECKPOINT_CORRUPT", error); }
    }
    private void write(Path file, Object data) {
        Path pending = file.resolveSibling(file.getFileName() + ".pending");
        try {
            byte[] bytes = json.writeValueAsBytes(data);
            try (FileChannel channel = FileChannel.open(pending, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                channel.write(java.nio.ByteBuffer.wrap(bytes)); channel.force(true);
            }
            Files.move(pending, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception error) { throw new IllegalStateException("PROBE_COMMIT_FAILED", error); }
    }
    public Journal journal(Scope scope) { return locked(task(scope, "journal.json"), () -> read(task(scope, "journal.json"), Journal.class, empty())); }
    private Journal update(Scope scope, Function<Journal, Journal> mutation) {
        Path file = task(scope, "journal.json");
        return locked(file, () -> { Journal updated = mutation.apply(read(file, Journal.class, empty())); write(file, updated); return updated; });
    }
    public ModelGateway model() {
        return (scope, messages, definitions) -> {
            Journal current = update(scope, old -> new Journal(old.prefix(), old.turns(), old.operations(), old.effects(), old.modelCalls() + 1));
            if (current.modelCalls() == 1) return CompletableFuture.completedFuture(new Round("", List.of(call("call-1"), call("call-2")), "tool_calls", 3, 2));
            if (messages.stream().flatMap(message -> message.observations().stream()).anyMatch(result -> result.status() == OperationStatus.DENIED)) {
                return CompletableFuture.completedFuture(new Round("tool denied", List.of(), "stop", 4, 1));
            }
            if (messages.stream().flatMap(message -> message.observations().stream()).filter(result -> result.status() == OperationStatus.SUCCEEDED).count() < 2) {
                return CompletableFuture.failedFuture(new IllegalStateException("RECOVERY_REINFERRED_WITHOUT_RESULTS effects=" + current.effects() + " observations=" + messages.stream().flatMap(message -> message.observations().stream()).map(Observation::status).toList()));
            }
            return CompletableFuture.completedFuture(new Round("done", List.of(), "stop", 4, 1));
        };
    }
    public PolicyPort policy() {
        return (scope, call) -> revoked ? Decision.DENY : ask && !approved.contains(call.id()) ? Decision.ASK : Decision.ALLOW;
    }
    @Override public CompletionStage<List<Message>> beforeModel(Scope scope, List<Message> history) {
        update(scope, old -> old.prefix().isEmpty() ? new Journal(history.stream().filter(message -> message.role() != Role.SYSTEM).toList(), old.turns(), old.operations(), old.effects(), old.modelCalls()) : old);
        return CompletableFuture.completedFuture(history);
    }
    @Override public CompletionStage<Void> afterModelCommitted(Scope scope, Turn turn) { return commitRound(scope, turn); }
    @Override public CompletionStage<Void> commitRound(Scope scope, Turn turn) {
        if (failRound) return CompletableFuture.failedFuture(new IllegalStateException("ROUND_COMMIT_FAILED"));
        CompletionStage<Void> gate = delayedRound == null ? CompletableFuture.completedFuture(null) : delayedRound;
        return gate.thenRun(() -> {
            update(scope, old -> {
                var turns = new ArrayList<>(old.turns()); turns.add(turn);
                var operations = new LinkedHashMap<>(old.operations());
                for (Call call : turn.round().calls()) if (operations.putIfAbsent(call.id(), new Operation(call, OperationStatus.PREPARED, null)) != null) throw new IllegalStateException("CALL_ID_REUSED");
                return new Journal(old.prefix(), List.copyOf(turns), operations, old.effects(), old.modelCalls());
            });
            seal(scope, Bundle.baseline()); crash("AFTER_ROUND");
        });
    }
    @Override public CompletionStage<Void> beforeTool(Scope scope, Call call) {
        crash("BEFORE_TOOL");
        try {
            update(scope, old -> {
                Operation op = old.operations().get(call.id());
                if (op == null || !op.call().equals(call)) throw new IllegalStateException("UNCOMMITTED_TOOL");
                if (op.status() != OperationStatus.PREPARED) throw new IllegalStateException("TOOL_RESULT_UNKNOWN_OR_ALREADY_EXECUTED");
                var ops = new LinkedHashMap<>(old.operations()); ops.put(call.id(), new Operation(call, OperationStatus.RUNNING, null));
                return new Journal(old.prefix(), old.turns(), ops, old.effects(), old.modelCalls());
            });
            return CompletableFuture.completedFuture(null);
        } catch (Exception error) { return CompletableFuture.failedFuture(error); }
    }
    @Override public CompletionStage<Observation> execute(Scope scope, Call call) {
        update(scope, old -> new Journal(old.prefix(), old.turns(), old.operations(), old.effects() + 1, old.modelCalls()));
        crash("AFTER_TOOL_EFFECT");
        return CompletableFuture.completedFuture(new Observation(call.id(), call.name(), "executed:" + call.id(), OperationStatus.SUCCEEDED));
    }
    @Override public CompletionStage<Void> commitResult(Scope scope, Call call, Observation result) {
        if (failResult) return CompletableFuture.failedFuture(new IllegalStateException("RESULT_COMMIT_FAILED"));
        update(scope, old -> {
            var ops = new LinkedHashMap<>(old.operations()); ops.put(call.id(), new Operation(call, result.status(), result));
            return new Journal(old.prefix(), old.turns(), ops, old.effects(), old.modelCalls());
        });
        seal(scope, Bundle.baseline()); crash("AFTER_TOOL_COMMIT");
        return CompletableFuture.completedFuture(null);
    }
    @Override public CompletionStage<Void> afterToolCommitted(Scope scope, Call call, Observation result) { return commitResult(scope, call, result); }
    @Override public CompletionStage<Completion> beforeStop(Scope scope, Message candidate) {
        boolean success = !candidate.text().isBlank() && journal(scope).operations().values().stream().allMatch(op -> op.status() == OperationStatus.SUCCEEDED);
        return CompletableFuture.completedFuture(success ? Completion.PASS : Completion.INCOMPLETE);
    }
    @Override public StateSlot load(Scope scope, String key) {
        return locked(states(scope), () -> read(states(scope), Slots.class, new Slots(Map.of())).values().getOrDefault(key, new StateSlot(0, null)));
    }
    @Override public long compareAndSwap(Scope scope, String key, long expected, byte[] body) {
        return locked(states(scope), () -> {
            var slots = new LinkedHashMap<>(read(states(scope), Slots.class, new Slots(Map.of())).values());
            StateSlot old = slots.getOrDefault(key, new StateSlot(0, null));
            if (expected >= 0 && old.version() != expected) return -1L;
            long version = old.version() + 1; slots.put(key, new StateSlot(version, body)); write(states(scope), new Slots(slots)); return version;
        });
    }
    @Override public Set<String> keys(Scope scope) { return locked(states(scope), () -> Set.copyOf(read(states(scope), Slots.class, new Slots(Map.of())).values().keySet())); }
    @Override public void delete(Scope scope, String key) {
        locked(states(scope), () -> { var slots = new LinkedHashMap<>(read(states(scope), Slots.class, new Slots(Map.of())).values()); slots.remove(key); write(states(scope), new Slots(slots)); return null; });
    }
    @Override public void seal(Scope scope, Bundle bundle) {
        var versions = new TreeMap<String, Long>(); var hashes = new TreeMap<String, String>();
        for (String key : keys(scope)) { StateSlot slot = load(scope, key); versions.put(key, slot.version()); hashes.put(key, com.agentplatform.types.harness.HarnessData.sha256(Base64.getEncoder().encodeToString(slot.body()))); }
        Journal ledger = journal(scope);
        Path file = task(scope, "manifest.json");
        locked(file, () -> { write(file, new Manifest(bundle.hash(), bundle.stateSchemaVersion(), ledger.turns().size(), versions, hashes)); return null; });
    }
    @Override public void verifyRestore(Scope scope, Bundle bundle) {
        Path file = task(scope, "manifest.json");
        Manifest manifest = locked(file, () -> read(file, Manifest.class, null));
        if (manifest == null || !manifest.bundleHash().equals(bundle.hash()) || manifest.schema() != bundle.stateSchemaVersion()) throw new IllegalStateException("CHECKPOINT_INCOMPATIBLE");
        Journal ledger = journal(scope);
        if (manifest.watermark() != ledger.turns().size()) throw new IllegalStateException("CHECKPOINT_INCOMPLETE");
        if (ledger.operations().values().stream().anyMatch(op -> op.status() == OperationStatus.RUNNING || op.status() == OperationStatus.UNKNOWN)) throw new IllegalStateException("TOOL_RESULT_UNKNOWN");
        if (!keys(scope).equals(manifest.versions().keySet())) throw new IllegalStateException("CHECKPOINT_INCOMPLETE");
        for (String key : keys(scope)) {
            StateSlot slot = load(scope, key);
            if (slot.version() != manifest.versions().get(key) || !com.agentplatform.types.harness.HarnessData.sha256(Base64.getEncoder().encodeToString(slot.body())).equals(manifest.hashes().get(key))) throw new IllegalStateException("CHECKPOINT_CORRUPT");
        }
    }
    @Override public List<Message> recoveryHistory(Scope scope) {
        Journal ledger = journal(scope); var history = new ArrayList<>(ledger.prefix());
        for (Turn turn : ledger.turns()) {
            history.add(new Message(Role.ASSISTANT, turn.round().text(), turn.round().calls(), List.of()));
            for (Call call : turn.round().calls()) {
                Operation op = ledger.operations().get(call.id());
                if (op.result() != null) history.add(new Message(Role.TOOL, "", List.of(), List.of(op.result())));
            }
        }
        return List.copyOf(history);
    }
    private static void crash(String point) { if (point.equals(System.getProperty("probe.crash"))) Runtime.getRuntime().halt(73); }
}
