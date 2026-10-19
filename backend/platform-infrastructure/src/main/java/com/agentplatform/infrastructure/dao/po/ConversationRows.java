package com.agentplatform.infrastructure.dao.po;
import java.util.UUID;
import java.time.Instant;
public final class ConversationRows {
    private ConversationRows() {}
    public record MessageRow2(Boolean value1) {}
    public record TaskRow3(Object value1) {}
    public record TaskRow6(String value1) {}
    public record TaskRow10(String configJson, Object id, String configHash, Object bundleId, Boolean credentialActive, Boolean modelActive) {}
    public record ConversationRow16(UUID id, String title, Long version, Instant updatedAt) implements ChatProjections.ConversationProjection {}
    public record TaskRow17(UUID id, UUID conversationId, String status, Boolean cancelRequested, String reasonCode, Long version) implements ChatProjections.TaskProjection {}
    public record ConversationRow18(UUID id, String title, Long version, Instant updatedAt) implements ChatProjections.ConversationProjection {}
    public record AuthorizeChatCredentialRow25(Boolean value1, String value2) {}
    public record TaskRow26(UUID value1, UUID value2, UUID value3, UUID value4, Instant value5) {}
    public record StoppedOwnerProofRow27(String value1) {}
    public record MessageRow29(String value1, Long value2) {}
    public record MessageRow30(Long value3, UUID value1, String value2) {}
    public record AuthorizeChatCredentialRow31(Boolean value1) {}
    public record MessageRow36(String value1, Long value2) {}
    public record MessageRow37(String value2, String value1) {}
    public record MessageRow41(UUID value1, Long value2) {}
    public record TaskRow43(String value1) {}
    public record TaskRow44(UUID creatorId, UUID conversationId, UUID modelId, UUID credentialId, Long credentialRevision, Long generation, String dispatchStatus, String status) {}
    public record TaskRow45(UUID value1) {}
    public record PlatformEventRow51(UUID id, UUID taskId, Long seq, String type, Long entityVersion, Instant createdAt, String payloadJson) {}
    public record PlatformEventRow52(Long value1) {}
    public record ConversationRow53(Long value1) {}
    public record TaskRow57(Long value1) {}
    public record TaskRow58(Long value1) {}
    public record TaskRow61(UUID id, UUID conversationId, String status, Boolean cancelRequested, String reasonCode, Long version) implements ChatProjections.TaskProjection {}
    public record MessageRow62(UUID id, UUID taskId, String role, String blocksJson, String status, Long version) {}
    public record ConversationRow63(Long value1) {}
    public record ConversationRow64(UUID id, String title, Long version, Instant updatedAt) implements ChatProjections.ConversationProjection {}
    public record ConversationRow65(UUID id, String title, Long version, Instant updatedAt) implements ChatProjections.ConversationProjection {}
    public record ConversationRow66(UUID id, String title, Long version, Instant updatedAt) implements ChatProjections.ConversationProjection {}
    public record ConversationRow68(Long value1) {}
    public record IdempotencyRecordRow69(String value1, UUID value2) {}
    public record TaskRow71(UUID id, UUID conversationId, String status, Boolean cancelRequested, String reasonCode, Long version) implements ChatProjections.TaskProjection {}
    public record AuthorizeChatActorRow73(Boolean value1) {}
    public record TaskRow174(String value1) {}
    public record MessageRow186(String value1, String value2) {}
}
