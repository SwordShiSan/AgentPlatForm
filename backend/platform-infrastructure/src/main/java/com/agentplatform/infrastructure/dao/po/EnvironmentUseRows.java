package com.agentplatform.infrastructure.dao.po;

import java.time.Instant;
import java.util.UUID;

public final class EnvironmentUseRows {
    private EnvironmentUseRows() {}
    public record Physical(String containerId,String startedAt,String readinessHash) {}
    public record Use(UUID id,UUID spaceId,UUID workspaceId,UUID environmentId,UUID actorId,UUID ownerId,UUID requestId,
            long generation,long fence,UUID creationCommandId,long creationFence,int projectId,String imageId,String policyHash,
            String containerId,String containerStartedAt,String kind,String requestHash,String requestText,
            UUID runnerInstanceId,String status,String resultState,String execId,boolean cancelRequested,String errorCode,UUID stopOperationId,
            Instant leaseUntil,Instant executeUntil,Instant deadlineAt,long version) {}
    public record Result(String resultText,String contentHash,long exitCode,boolean truncated,UUID runnerInstanceId,UUID priorRunnerProofId,
            String writerKind,UUID writerOwnerId,Instant createdAt) {}
}
