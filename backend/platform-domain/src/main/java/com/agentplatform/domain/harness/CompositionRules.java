package com.agentplatform.domain.harness;

import com.agentplatform.types.harness.HarnessData.Bundle;
import java.util.Set;

public final class CompositionRules {
    private CompositionRules() {}
    public static void validate(Bundle bundle, int compactionOwners, int memoryWriters, boolean sdkBypassEnabled) {
        if (!bundle.sdkVersion().equals("2.0.4") || bundle.contractVersion() != 1 || bundle.stateSchemaVersion() != 1) throw new IllegalArgumentException("CHECKPOINT_INCOMPATIBLE");
        if (!bundle.codecVersion().equals("platform-v1") || !bundle.modelFamily().equals("scripted")) throw new IllegalArgumentException("PROVIDER_STATE_INCOMPATIBLE");
        if (!bundle.components().keySet().equals(Set.of("loop", "context", "memory", "retrieval", "completion"))) throw new IllegalArgumentException("BUNDLE_INCOMPATIBLE");
        for (var entry : bundle.components().entrySet()) {
            if (!entry.getValue().equals(entry.getKey().equals("loop") ? "agentscope-v1" : entry.getKey() + "-v1")) throw new IllegalArgumentException("COMPONENT_UNAVAILABLE");
        }
        if (compactionOwners != 1 || memoryWriters != 1 || sdkBypassEnabled) throw new IllegalArgumentException("DUPLICATE_AUTHORITY");
    }
}
