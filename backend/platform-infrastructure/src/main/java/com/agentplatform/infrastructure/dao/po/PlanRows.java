package com.agentplatform.infrastructure.dao.po;

import java.time.Instant;
import java.util.UUID;

public final class PlanRows {
    private PlanRows() {}
    public record PlanRow(UUID id,UUID spaceId,UUID taskId,long currentRevision,String status,long version) {}
    public record RevisionRow(UUID id,long revision,String title,String markdown,String source,String contentHash,Instant createdAt) {}
    public record InteractionRow(UUID id,UUID spaceId,UUID taskId,UUID sourceAttemptId,String callId,String kind,boolean optional,
                                 String payloadJson,UUID revisionId,long version,String status,Instant expiresAt) {}
    public record ControlRow(UUID id,UUID spaceId,UUID taskId,UUID sourceAttemptId,String callId,String toolName,String callHash,
                             String argumentsJson,UUID interactionId,String status,String resultJson) {}
    public record AnswerRow(UUID id,UUID interactionId,UUID taskId,UUID commandId,String requestHash,String decision,String contentJson) {}
    public record ResumeRow(UUID id,UUID taskId,UUID sourceAttemptId,UUID checkpointId,UUID answerId,String callId,String answerHash,
                            UUID reviewRevisionId,long generation,UUID targetAttemptId,String status) {}
}
