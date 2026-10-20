package com.agentplatform.types.harness;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/** HC-1 values. These are internal runtime projections, not HTTP entities. */
public final class HarnessData {
    private HarnessData() {}

    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }
    public enum Decision { ALLOW, ASK, DENY }
    public enum Completion { PASS, REPAIR, INCOMPLETE, WAITING_APPROVAL, CANCELLED }
    public enum OperationStatus { PREPARED, RUNNING, SUCCEEDED, UNKNOWN, DENIED }

    public record Scope(String spaceId, String actorId, String conversationId, String taskId,
                        String attemptId, long fence, boolean planMode) {
        public Scope {
            for (String id : List.of(spaceId, actorId, conversationId, taskId, attemptId)) UUID.fromString(id);
            if (fence < 1) throw new IllegalArgumentException("Invalid fence");
        }
    }

    public record Call(String id, String name, Map<String, Object> arguments, boolean readOnly) {
        public Call {
            if (id == null || id.isBlank() || name == null || name.isBlank()) throw new IllegalArgumentException("Missing call identity");
            arguments = freezeMap(arguments);
        }
    }

    public record Observation(String callId, String name, String text, OperationStatus status) {
        public Observation { Objects.requireNonNull(callId); Objects.requireNonNull(name); Objects.requireNonNull(text); Objects.requireNonNull(status); }
    }

    public record Message(Role role, String text, List<Call> calls, List<Observation> observations) {
        public Message {
            Objects.requireNonNull(role);
            text = text == null ? "" : text;
            calls = List.copyOf(calls);
            observations = List.copyOf(observations);
        }
        public static Message text(Role role, String text) { return new Message(role, text, List.of(), List.of()); }
    }

    public record Round(String text, List<Call> calls, String finishReason, long inputTokens, long outputTokens) {
        public Round {
            text = text == null ? "" : text;
            calls = List.copyOf(calls);
            Objects.requireNonNull(finishReason);
            if (inputTokens < 0 || outputTokens < 0) throw new IllegalArgumentException("Negative usage");
        }
    }

    public record Turn(String id, long ordinal, Round round) {
        public Turn { UUID.fromString(id); if (ordinal < 1) throw new IllegalArgumentException("Invalid turn"); Objects.requireNonNull(round); }
    }

    public record Approval(Call call, boolean allowed) { public Approval { Objects.requireNonNull(call); } }

    public record Bundle(String sdkVersion, int contractVersion, int stateSchemaVersion,
                         String codecVersion, String modelFamily, Map<String, String> components) {
        public Bundle {
            Objects.requireNonNull(sdkVersion); Objects.requireNonNull(codecVersion); Objects.requireNonNull(modelFamily);
            components = Collections.unmodifiableMap(new TreeMap<>(components));
            if (contractVersion < 1 || stateSchemaVersion < 1) throw new IllegalArgumentException("Invalid bundle schema");
        }
        public String hash() { return sha256(sdkVersion + "|" + contractVersion + "|" + stateSchemaVersion + "|" + codecVersion + "|" + modelFamily + "|" + components); }
        public static Bundle baseline() {
            return new Bundle("2.0.4", 1, 1, "platform-v1", "scripted", Map.of("loop", "agentscope-v1", "context", "context-v1", "memory", "memory-v1", "retrieval", "retrieval-v1", "completion", "completion-v1"));
        }
    }

    public record Request(Scope scope, Bundle bundle, List<Message> input, boolean restoring, List<Approval> approvals, long nextTurn) {
        public Request {
            Objects.requireNonNull(scope); Objects.requireNonNull(bundle);
            input = List.copyOf(input); approvals = List.copyOf(approvals);
            if (restoring && !input.isEmpty()) throw new IllegalArgumentException("Recovery input comes from ledger, not caller messages");
            if (input.stream().anyMatch(message -> message.role() != Role.USER)) throw new IllegalArgumentException("Only user input is accepted; control/history use separate ports");
            if (nextTurn < 1) throw new IllegalArgumentException("Invalid next turn");
            if (!restoring && !approvals.isEmpty()) throw new IllegalArgumentException("Approval requires restoration");
        }
    }

    public record Outcome(Completion completion, String text, List<Call> pending) {
        public Outcome { Objects.requireNonNull(completion); text = text == null ? "" : text; pending = List.copyOf(pending); }
    }

    public record StateSlot(long version, byte[] body) {
        public StateSlot { if (version < 0) throw new IllegalArgumentException("Invalid state version"); body = body == null ? null : body.clone(); }
        @Override public byte[] body() { return body == null ? null : body.clone(); }
    }

    public enum CheckpointPhase { INPUT_READY, MODEL_COMMITTED, TOOL_COMMITTED, TERMINAL }
    /** Defensive copy, not a reference to mutable SDK state or a second message authority. */
    public record CheckpointDraft(long expectedVersion, byte[] state, List<Message> history, CheckpointPhase phase) {
        public CheckpointDraft {
            if (expectedVersion < 0 || state == null || state.length == 0 || state.length > 1048576)
                throw new IllegalArgumentException("Invalid checkpoint state");
            state = state.clone(); history = List.copyOf(history); Objects.requireNonNull(phase);
            if (history.isEmpty()) throw new IllegalArgumentException("Checkpoint input required");
        }
        @Override public byte[] state() { return state.clone(); }
    }

    public static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static Map<String, Object> freezeMap(Map<String, Object> values) {
        var result = new LinkedHashMap<String, Object>();
        values.forEach((key, value) -> result.put(Objects.requireNonNull(key), freeze(value)));
        return Collections.unmodifiableMap(result);
    }
    private static Object freeze(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach((key, nested) -> result.put((String) key, freeze(nested)));
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof List<?> list) {
            var result = new ArrayList<Object>(); list.forEach(item -> result.add(freeze(item))); return Collections.unmodifiableList(result);
        }
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        throw new IllegalArgumentException("Non-JSON call argument");
    }
}
