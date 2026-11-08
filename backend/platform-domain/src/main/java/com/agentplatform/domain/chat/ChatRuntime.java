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
    public static Bundle contextBundle(boolean workspace) {
        var old=workspace?workspaceBundle():bundle();
        var components=new java.util.TreeMap<>(old.components());
        components.put("context","canonical-large-context-v2");
        return new Bundle(old.sdkVersion(),old.contractVersion(),old.stateSchemaVersion(),old.codecVersion(),old.modelFamily(),components);
    }
    public static Bundle compactionBundle(boolean workspace) {
        var old=contextBundle(workspace);
        var components=new java.util.TreeMap<>(old.components());
        components.put("context","canonical-context-compaction-v1");
        components.put("compaction","extractive-summary-v1");
        return new Bundle(old.sdkVersion(),old.contractVersion(),old.stateSchemaVersion(),old.codecVersion(),old.modelFamily(),components);
    }
    public static Bundle evidenceBundle(boolean workspace,boolean compaction) {
        var base=workspace?workspaceBundle():bundle();
        var components=new java.util.TreeMap<>(base.components());
        components.put("context",compaction?"canonical-context-evidence-compaction-v1":"canonical-context-evidence-v1");
        components.put("memory","reviewed-personal-memory-v1");
        components.put("retrieval","authorized-knowledge-reference-v1");
        if(compaction)components.put("compaction","extractive-summary-v1");
        return new Bundle(base.sdkVersion(),base.contractVersion(),base.stateSchemaVersion(),base.codecVersion(),base.modelFamily(),components);
    }
    public static Bundle workspaceBundle() {
        var components=new java.util.TreeMap<>(bundle().components());
        components.put("loop","agentscope-workspace-tools-v6");
        components.put("context","canonical-workspace-input-v5");
        components.put("tools",WorkspaceTools.VERSION);
        components.put("plan-controls","scoped-plan-review-v2");
        components.put("execution-control","remote-tool-termination-v1");
        components.put("tool-results","known-failure-v1");
        return new Bundle("2.0.4",1,1,"platform-v1","deepseek-flash",components);
    }
}
