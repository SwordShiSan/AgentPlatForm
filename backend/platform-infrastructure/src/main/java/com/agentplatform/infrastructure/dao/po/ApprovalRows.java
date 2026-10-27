package com.agentplatform.infrastructure.dao.po;

import java.time.Instant;
import java.util.UUID;

public final class ApprovalRows {
    private ApprovalRows() {}
    public record OperationRow(UUID id, UUID spaceId, UUID taskId, String callId, String callJson,
                               String callHash, String status, String resultJson, String resultHash) {}
    public record BindingRow(UUID interactionId, UUID spaceId, UUID taskId, UUID actorId, UUID sourceAttemptId,
                             UUID operationId, String callId, String callHash, String fingerprint,
                             UUID workspaceId, String resourceHash, long permissionVersion, String policyVersion,
                             String summary, String reasonCode, Instant expiresAt, String interactionStatus) {}
    public record ConsumptionRow(UUID interactionId, UUID answerId, UUID operationId, UUID attemptId,
                                 long fence, String outcome) {}
}
