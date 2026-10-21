package com.agentplatform.infrastructure.dao.po;

import java.time.Instant;
import java.util.UUID;

public final class WorkspaceRows {
    private WorkspaceRows() {}
    public record WorkspaceRow(UUID id, UUID spaceId, UUID actorId, String name, String state,
            String environmentState, long quotaBytes, long quotaInodes, int projectId, long version,
            UUID operationId, String errorCode, Instant createdAt) {}
    public record CapacityRow(long reservedBytes, int reservedSlots, int nextProjectId) {}
    public record RequestRow(String requestHash, UUID workspaceId, UUID operationId) {}
    public record OperationRow(UUID id, UUID spaceId, UUID workspaceId, UUID actorId, UUID commandId,
            long generation, long fence, String status, String effectState, UUID ownerId,
            Instant leaseUntil, Instant deadlineAt, long version) {}
    public record PublicationRow(UUID id, UUID operationId, UUID spaceId, UUID workspaceId,
            long generation, long fence, UUID relayOwner) {}
    public record CommandRow(UUID commandId, String requestHash, String state, long resultRevision) {}
    public record ReceiptRow(String resultJson, String contentHash) {}
}
