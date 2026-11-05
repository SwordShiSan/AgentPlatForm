package com.agentplatform.domain.chat;

import java.util.Map;

/** Frozen request capacity; token quota and model capability are separate facts. */
public record ContextPolicy(int modelWindow, int taskWindow, int maxOutput,
                            int safetyTokens, int stateBytes, int checkpointBytes,
                            String version) {
    public static final String CURRENT_VERSION = "context-v2";
    public static final int MODEL_WINDOW = 1_000_000;
    public static final int DEFAULT_WINDOW = 262_144;
    public static final int MIN_WINDOW = 65_536;
    public static final int MAX_WINDOW = 524_288;
    public static final int DEFAULT_OUTPUT = 4_096;
    public static final int MAX_OUTPUT = 8_192;
    public static final int MAX_STATE_BYTES = 8 * 1024 * 1024;
    public static final int MAX_CHECKPOINT_BYTES = 16 * 1024 * 1024;

    public ContextPolicy {
        if (modelWindow < 1 || taskWindow < 1 || maxOutput < 1 || safetyTokens < 0
                || stateBytes < 1 || checkpointBytes < stateBytes || version == null
                || maxOutput + safetyTokens >= Math.min(modelWindow, taskWindow)) {
            throw new IllegalArgumentException("Invalid context policy");
        }
    }

    public static ContextPolicy current(int window, int output) {
        if (window < MIN_WINDOW || window > MAX_WINDOW || output < 1 || output > MAX_OUTPUT)
            throw new IllegalArgumentException("Context policy is outside configured bounds");
        int safety = Math.max(8_192, (window + 19) / 20);
        return new ContextPolicy(MODEL_WINDOW, window, output, safety,
                MAX_STATE_BYTES, MAX_CHECKPOINT_BYTES, CURRENT_VERSION);
    }

    public static ContextPolicy legacy() {
        return new ContextPolicy(8_192, 8_192, 1_024, 0, 1_048_576,
                2 * 1024 * 1024, "legacy-v1");
    }

    public static ContextPolicy fromSnapshot(Map<String, ?> snapshot) {
        Object version = snapshot.get("contextPolicyVersion");
        if (version == null) return legacy();
        if (!CURRENT_VERSION.equals(version)) throw new ChatFailure(409, "CONTEXT_POLICY_INCOMPATIBLE");
        try {
            var policy = current(((Number) snapshot.get("window")).intValue(),
                    ((Number) snapshot.get("maxOutput")).intValue());
            if (((Number) snapshot.get("modelWindow")).intValue() != policy.modelWindow()
                    || ((Number) snapshot.get("contextSafety")).intValue() != policy.safetyTokens()
                    || ((Number) snapshot.get("stateBytes")).intValue() != policy.stateBytes()
                    || ((Number) snapshot.get("checkpointBytes")).intValue() != policy.checkpointBytes())
                throw new IllegalArgumentException("Frozen context policy differs");
            return policy;
        } catch (RuntimeException invalid) {
            throw new ChatFailure(409, "CONTEXT_POLICY_INCOMPATIBLE");
        }
    }

    public long inputLimit() { return (long) Math.min(modelWindow, taskWindow) - maxOutput - safetyTokens; }

    public long reserveTokens(long estimatedInput) {
        if (estimatedInput < 0 || estimatedInput > inputLimit())
            throw new ChatFailure(422, "CONTEXT_REQUIRED_TOO_LARGE");
        if (!CURRENT_VERSION.equals(version)) return 9_216;
        long cushion = Math.max(512, (estimatedInput + 19) / 20);
        return Math.addExact(Math.min(inputLimit(), Math.addExact(estimatedInput, cushion)), maxOutput);
    }
}
