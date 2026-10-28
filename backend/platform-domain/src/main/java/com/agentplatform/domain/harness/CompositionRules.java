package com.agentplatform.domain.harness;

import com.agentplatform.types.harness.HarnessData.Bundle;
import java.util.Set;
import java.util.Map;

public final class CompositionRules {
    private CompositionRules() {}
    public static void validate(Bundle bundle, int compactionOwners, int memoryWriters, boolean sdkBypassEnabled) {
        if (!bundle.sdkVersion().equals("2.0.4") || bundle.contractVersion() != 1 || bundle.stateSchemaVersion() != 1) throw new IllegalArgumentException("CHECKPOINT_INCOMPATIBLE");
        if (!bundle.codecVersion().equals("platform-v1") || !Set.of("scripted", "deepseek-flash").contains(bundle.modelFamily())) throw new IllegalArgumentException("PROVIDER_STATE_INCOMPATIBLE");
        Map<String,String> expected = bundle.modelFamily().equals("scripted") ? Bundle.baseline().components()
                : bundle.components().containsKey("tools") ? com.agentplatform.domain.chat.ChatRuntime.workspaceBundle().components()
                : com.agentplatform.domain.chat.ChatRuntime.bundle().components();
        if (!bundle.components().keySet().equals(expected.keySet())) throw new IllegalArgumentException("BUNDLE_INCOMPATIBLE");
        if (!bundle.components().equals(expected)) throw new IllegalArgumentException("COMPONENT_UNAVAILABLE");
        if (compactionOwners != 1 || memoryWriters != 1 || sdkBypassEnabled) throw new IllegalArgumentException("DUPLICATE_AUTHORITY");
    }
}
