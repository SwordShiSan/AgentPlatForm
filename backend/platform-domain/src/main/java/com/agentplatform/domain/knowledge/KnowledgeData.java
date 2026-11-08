package com.agentplatform.domain.knowledge;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class KnowledgeData {
    private KnowledgeData() {}
    public record Actor(UUID userId, UUID spaceId) {}
    public record Base(UUID id, UUID spaceId, UUID ownerId, UUID workspaceId, String name, String state, long revision) {}
    public record Document(UUID id, UUID baseId, String sourceKind, UUID fileId, UUID versionId, UUID attachmentId,
                          UUID extractionId, String name, String state, UUID activeIndexVersion, String sourceSha256, long revision) {}
    public record ExtractedSource(UUID extractionId, String sourceKind, UUID workspaceId, UUID fileId, UUID versionId,
                                  UUID attachmentId, UUID sourceAssetId, String sourceSha256, String extractionSha256,
                                  long extractionBytes, String extractedObjectKey, String name, String sourceFormat) {}
    public record Publication(UUID id, UUID buildId, UUID spaceId, long generation, UUID relayOwner) {}
    public record BuildCommand(UUID id, UUID buildId, UUID documentId, UUID indexVersion, UUID spaceId, UUID actorId, UUID ownerId,
                               long generation, long fence, Instant leaseUntil, UUID extractionId,
                               String extractedObjectKey, long extractedBytes, String extractedSha256,
                               String sourceKind, String sourceFormat, String displayName, String modelSha256, String tokenizerSha256) {}
    public record Chunk(int ordinal, String body, String sha256, String locationJson, long start, long end, String vector) {}
    public record Hit(UUID documentId, UUID indexVersion, String sourceKind, UUID versionId, UUID attachmentId, String name, String body,
                      String locationJson, double score, String sourceSha256) {}
    public record Memory(UUID id, String scopeKind, UUID workspaceId, String category, String topicKey,
                         String content, String status, long revision, Instant expiresAt, Instant confirmedAt) {}
    public record MemoryRecall(List<Memory> items, boolean degraded, String reason) {
        public MemoryRecall { items = List.copyOf(items); }
    }
}
