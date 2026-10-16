package com.agentplatform.domain.chat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ChatData {
    private ChatData() {}
    public record Actor(UUID userId, UUID spaceId) {}
    public record Model(UUID id, String name, long version) {}
    public record Conversation(UUID id, String title, long version, Instant updatedAt) {}
    public record Task(UUID id, UUID conversationId, String status, boolean cancelRequested, String reasonCode, long version) {}
    public record Message(UUID id, UUID taskId, String role, List<Map<String,Object>> blocks, String status, long version) {}
    public record Snapshot(UUID conversationId, long cursor, List<Message> messages, List<Task> tasks) {}
    public record Event(UUID id, UUID spaceId, UUID conversationId, UUID taskId, long seq, String type, long version, Instant occurredAt, Map<String,Object> payload) {}
    public record Envelope(int schemaVersion, UUID eventId, UUID spaceId, UUID aggregateId, long generation, String kind, Instant occurredAt, String traceparent, UUID payloadRef) {}
    public record Publish(UUID id, Envelope envelope) {}
    public record Lease(UUID taskId, UUID spaceId, UUID userId, UUID conversationId, UUID attemptId, UUID ownerId, long fence, UUID modelId, UUID credentialId, long credentialRevision, UUID assistantId) {}
    public record Credential(UUID id, String scope, UUID spaceId, UUID ownerId, byte[] ciphertext, byte[] nonce, int keyVersion, long revision) {
        public Credential {
            if (id == null || !("PLATFORM".equals(scope) || "USER".equals(scope))
                    || ("PLATFORM".equals(scope) && (spaceId != null || ownerId != null))
                    || ("USER".equals(scope) && (spaceId == null || ownerId == null))
                    || ciphertext == null || nonce == null || nonce.length != 12 || keyVersion < 1 || revision < 1) {
                throw new IllegalArgumentException("Invalid credential metadata");
            }
            ciphertext = ciphertext.clone();
            nonce = nonce.clone();
        }
        @Override public byte[] ciphertext() { return ciphertext.clone(); }
        @Override public byte[] nonce() { return nonce.clone(); }
        @Override public String toString() { return "Credential[id=" + id + ", scope=" + scope + ", revision=" + revision + "]"; }
    }
    public record Call(UUID id, int ordinal, String priceVersion) {}
    public record Usage(UUID callId, String status, Long inputTokens, Long outputTokens, Long costMicros, boolean estimated) {}
}
