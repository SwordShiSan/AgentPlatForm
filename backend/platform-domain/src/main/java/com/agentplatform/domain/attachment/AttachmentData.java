package com.agentplatform.domain.attachment;

import java.time.Instant;
import java.util.UUID;

/** Attachment source identities never imply a workspace or an execution permission. */
public final class AttachmentData {
    private AttachmentData() {}
    public record Create(UUID draftId, String filename, long bytes, String sha256) {}
    public record Upload(UUID id, UUID spaceId, UUID actorId, UUID attachmentId, UUID requestId,
            String requestHash, String status, UUID transferOwner, Instant transferUntil, long revision,
            Instant expiresAt, String filename, long sizeBytes, String sha256, String objectKey) {}
    public record Attachment(UUID id, UUID spaceId, UUID actorId, UUID assetId, UUID draftId,
            UUID conversationId, String filename, long sizeBytes, String sha256, String status,
            long revision, String objectKey) {}
    public record Category(String category,long bytes,long objects) {}
    public record Usage(long objectLimitBytes,long usedBytes,long reservedBytes,long retainedBytes,java.util.List<Category> categories) {
        public Usage { categories=java.util.List.copyOf(categories); }
    }
}
