package com.agentplatform.domain.chat;

import com.agentplatform.types.harness.HarnessData.Bundle;
import java.util.Map;

/** The closed text-only composition; changing these identities requires a new accepted bundle. */
public final class ChatRuntime {
    private ChatRuntime() {}
    public static Bundle bundle() {
        return new Bundle("2.0.4", 1, 1, "platform-v1", "deepseek-flash", Map.of(
                "loop", "agentscope-checkpoint-recovery-v3-fixed-fixture-v1", "context", "canonical-frozen-chat-v2",
                "memory", "disabled", "retrieval", "disabled", "completion", "text-completion-v1"));
    }
}
