package com.agentplatform.domain.chat;

import com.agentplatform.types.harness.HarnessData.Bundle;
import java.util.Map;

/** The closed text-only composition; changing these identities requires a new accepted bundle. */
public final class ChatRuntime {
    private ChatRuntime() {}
    public static Bundle bundle() {
        return new Bundle("2.0.4", 1, 1, "platform-v1", "deepseek-flash", Map.of(
                "loop", "agentscope-durable-approval-v5", "context", "canonical-approval-input-v4",
                "memory", "disabled", "retrieval", "disabled", "completion", "text-completion-v1",
                "policy", RiskPolicy.VERSION));
    }
    public static Bundle workspaceBundle() {
        var components=new java.util.TreeMap<>(bundle().components());
        components.put("loop","agentscope-workspace-tools-v6");
        components.put("context","canonical-workspace-input-v5");
        components.put("tools",WorkspaceTools.VERSION);
        components.put("execution-control","remote-tool-termination-v1");
        components.put("tool-results","known-failure-v1");
        return new Bundle("2.0.4",1,1,"platform-v1","deepseek-flash",components);
    }
}
