package com.agentplatform.infrastructure.dao.po;

import java.util.UUID;

public final class EnvironmentLifecycleRows {
    private EnvironmentLifecycleRows() {}

    public record Target(UUID pendingOperationId, String containerId, String containerStartedAt,
                         UUID priorStopReceiptId, String protocol, String cleanupState) {}
    public record ActiveUse(UUID id, String kind, String status) {}
    public record Admission(UUID operationId, String intent, String status, long generation, long version) {}
    public record Plan(UUID id, UUID spaceId, UUID workspaceId, UUID environmentId, UUID actorId,
                       UUID commandId, long generation, long fence, String kind, String status,
                       UUID creationCommandId, long creationFence, String imageId, String policyHash,
                       int projectId, String containerId, String containerStartedAt, UUID priorStopReceiptId) {}
}
