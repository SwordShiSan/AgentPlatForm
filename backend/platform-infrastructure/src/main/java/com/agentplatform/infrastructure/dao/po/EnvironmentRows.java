package com.agentplatform.infrastructure.dao.po;

import java.util.UUID;

public final class EnvironmentRows {
    private EnvironmentRows() {}
    public record Slot(UUID spaceId, UUID workspaceId, UUID environmentId, Long generation, long revision) {}
    public record Environment(UUID id, UUID spaceId, UUID workspaceId, long generation, long fence, String state,
            String imageId, String policyHash, UUID creationCommandId, long creationFence,
            UUID operationId, long version, String errorCode) {}
    public record Operation(UUID id, UUID spaceId, UUID workspaceId, UUID environmentId, String kind,
            String status, String effectState) {}
    public record Request(String requestHash, UUID workspaceId, UUID environmentId, UUID operationId) {}
}
