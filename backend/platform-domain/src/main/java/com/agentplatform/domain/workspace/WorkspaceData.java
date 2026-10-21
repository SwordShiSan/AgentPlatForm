package com.agentplatform.domain.workspace;

import java.time.Instant;
import java.util.UUID;

public final class WorkspaceData {
    private WorkspaceData() {}
    public static final long QUOTA_BYTES = 2L * 1024 * 1024 * 1024;
    public static final long QUOTA_INODES = 100_000;
    public record Actor(UUID userId, UUID spaceId) {}
    public record Workspace(UUID id, UUID spaceId, UUID actorId, String name, String state,
            String environmentState, long quotaBytes, long quotaInodes, long version,
            UUID operationId, String errorCode, Instant createdAt) {}
    public record Command(UUID commandId, UUID operationId, UUID spaceId, UUID workspaceId,
            UUID actorId, UUID ownerId, long generation, long fence, String kind,
            long quotaBytes, long quotaInodes, int projectId, Instant deadline) {}
    public record Receipt(UUID commandId, UUID operationId, UUID workspaceId, long generation,
            long fence, String state, String effectState, String errorCode, String allocationHash, long revision) {}
    public record Publication(UUID id, UUID operationId, UUID spaceId, UUID workspaceId,
            long generation, long fence, UUID relayOwner) {}
}
