package com.agentplatform.infrastructure.dao.po;

import java.time.Instant;
import java.util.UUID;

public final class WorkspaceToolRows {
    private WorkspaceToolRows() {}
    public record Catalog(UUID taskId,UUID spaceId,UUID workspaceId,UUID actorId,String catalogVersion,UUID runtimeBundleId) {}
    public record PublicOperation(UUID operationId,UUID taskId,String toolName,String status,String phase,String summary,
                                  Instant createdAt,Instant endedAt,String errorCode,String traceId,String reviewJson,boolean resultAvailable) {}
    public record Resource(UUID workspaceId,UUID spaceId,UUID actorId,String state,long contentRevision,
                           long quotaBytes,long quotaInodes,Integer projectId,Long environmentVersion,String environmentState,
                           String environmentImageId,String environmentPolicyHash) {}
    public record Intent(UUID operationId,UUID spaceId,UUID taskId,UUID workspaceId,UUID actorId,String toolName,
                         String catalogVersion,String argsHash,long contentRevision,long permissionVersion,String policyVersion,
                         String resourceHash,String resourceJson,String reviewJson,UUID activationRequestId,Long expectedEnvironmentVersion,
                         String expectedImageId,String expectedPolicyHash,Instant createdAt) {}
}
