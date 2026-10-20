package com.agentplatform.infrastructure.dao.po;

import java.time.Instant;
import java.util.UUID;

public final class RecoveryRows {
    private RecoveryRows() {}
    public interface CheckpointIdentity { UUID id(); UUID attemptId(); String bundleHash(); String phase(); }
    public record CheckpointMeta(UUID id, UUID attemptId, String bundleHash, String phase) implements CheckpointIdentity {}
    public record TaskRow(UUID id, UUID spaceId, UUID userId, UUID conversationId, String status,
                          String reasonCode, boolean cancelRequested, long version, long revision, Instant deadline, UUID traceId) {}
    public record CheckpointRow(UUID id, UUID attemptId, String bundleHash, String phase, String manifestJson,
                                String manifestHash, String canonicalJson, String canonicalHash, String stateJson,
                                String stateHash, long watermark) implements CheckpointIdentity {}
    public record RequestRow(UUID commandId, UUID checkpointId, UUID sourceAttemptId, long revision,
                             String requestHash, String status, UUID targetAttemptId) {}
    public record TurnRow(UUID id, long ordinal, String status, String inputJson, String roundJson) {}
    public record OperationRow(UUID id, UUID turnId, String callJson, String status, String resultJson) {}
}
