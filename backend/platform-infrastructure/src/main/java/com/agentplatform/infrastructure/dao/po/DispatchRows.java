package com.agentplatform.infrastructure.dao.po;
import java.util.UUID;
import java.time.Instant;
public final class DispatchRows {
    private DispatchRows() {}
    public record EventOutboxRow4(String value1) {}
    public record ExecutionDispatchRow22(Long value1) {}
    public record ExecutionSlotRow28(UUID value1, UUID value2, Long value3, Instant value5, Instant value4) {}
    public record ExecutionSlotRow40(Instant value1) {}
    public record ExecutionSlotRow46(Object value1, Long value2) {}
    public record ExecutionDispatchRow48(UUID value1, Long value2, UUID value4, UUID value3) {}
    public record EventOutboxRow49(UUID id, UUID eventId, UUID spaceId, UUID aggregateId, Long generation, Instant createdAt) {}
    public record EventOutboxRow56(Long value1, Instant value2) {}
    public record ExecutionSlotRow74(Instant value5, UUID value1, UUID value2, Long value3, Instant value4) {}
}
