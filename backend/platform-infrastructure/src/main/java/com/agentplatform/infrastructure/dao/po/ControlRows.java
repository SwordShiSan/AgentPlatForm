package com.agentplatform.infrastructure.dao.po;
import java.util.UUID;
public final class ControlRows {
    private ControlRows() {}
    public record ControlCallRow(UUID id, UUID spaceId, UUID taskId, UUID attemptId, UUID userId, UUID conversationId, UUID ownerId, long fence, String status, String reservationState, long allowance, long revision, String priceVersion, String providerRequestId, String providerKind, String configJson, Long inputTokens, Long outputTokens, Long costMicros) {}
    public record ControlTaskRow(UUID spaceId, UUID userId, UUID conversationId, boolean live, boolean stopRequested, boolean overdue) {}
}
