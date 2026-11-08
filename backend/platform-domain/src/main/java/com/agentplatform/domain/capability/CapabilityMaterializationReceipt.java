package com.agentplatform.domain.capability;

import java.time.Instant;
import java.util.UUID;

/** Internal durable runner receipt; it is never returned by a public workspace API. */
public record CapabilityMaterializationReceipt(UUID commandId, UUID operationId, UUID workspaceId,
        long generation, long fence, String state, String manifestHash, String policyHash,
        int verifiedFiles, boolean readOnlyMounts, Integer exitCode, String containerId,
        String imageId, Instant startedAt, Instant finishedAt, String errorCode, long revision,
        boolean stopConfirmed) {
    public CapabilityMaterializationReceipt {
        if (commandId == null || operationId == null || workspaceId == null || generation < 1 || fence < 1
                || !java.util.Set.of("RUNNING", "COMMITTED", "BLOCKED", "FAILED").contains(state)
                || manifestHash == null || !manifestHash.matches("[a-f0-9]{64}") || revision < 1
                || verifiedFiles < 0 || errorCode != null && !errorCode.matches("[A-Z0-9_]{1,64}"))
            throw new IllegalArgumentException("INVALID_CAPABILITY_MATERIALIZATION_RECEIPT");
        if ("COMMITTED".equals(state) && (!stopConfirmed || !readOnlyMounts || exitCode == null || exitCode != 0
                || policyHash == null || !policyHash.matches("[a-f0-9]{64}") || containerId == null
                || imageId == null || startedAt == null || finishedAt == null))
            throw new IllegalArgumentException("INCOMPLETE_CAPABILITY_MATERIALIZATION_RECEIPT");
        if (stopConfirmed && containerId != null && finishedAt == null && startedAt != null)
            throw new IllegalArgumentException("INCOMPLETE_CAPABILITY_MATERIALIZATION_STOP_RECEIPT");
        if ("BLOCKED".equals(state) && stopConfirmed)
            throw new IllegalArgumentException("INVALID_BLOCKED_MATERIALIZATION_STOP_RECEIPT");
    }
}
