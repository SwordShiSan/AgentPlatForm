package com.agentplatform.domain.files;

import java.time.Instant;
import java.util.UUID;

public final class FileData {
    private FileData() {}
    public record Actor(UUID userId, UUID spaceId) {}
    public record UploadRequest(String path, long bytes, String sha256, UUID fileId, UUID baseVersionId, Long expectedRevision) {
        public UploadRequest {
            path = FileRules.path(path, false);
            sha256 = FileRules.sha256(sha256);
            if (bytes < 0 || bytes > FileRules.MAX_UPLOAD_BYTES) throw new FileFailure(413, "FILE_TOO_LARGE");
            if (fileId == null && (baseVersionId != null || expectedRevision != null)) throw new FileFailure(400, "INVALID_UPLOAD_MODE");
            if (fileId != null && (baseVersionId == null || expectedRevision == null)) throw new FileFailure(428, "VERSION_REQUIRED");
            if (expectedRevision != null && expectedRevision < 1) throw new FileFailure(400, "INVALID_VERSION");
        }
        public boolean replacement() { return fileId != null; }
    }
    public record Upload(UUID id, UUID spaceId, UUID workspaceId, UUID actorId, UUID requestId, String requestHash,
            String relativePath, String mode, UUID fileId, UUID baseVersionId, Long expectedRevision, UUID versionId,
            UUID assetId, String objectKey, long sizeBytes, String sha256, String status, UUID transferOwner,
            Instant transferUntil, UUID operationId, Instant expiresAt, long revision) {}
    public record Entry(UUID id, UUID spaceId, UUID workspaceId, String relativePath, String parentPath, String name,
            String kind, UUID currentVersionId, String collectionState, long revision, String sha256, Long sizeBytes) {}
    public record Version(UUID id, UUID spaceId, UUID workspaceId, UUID fileId, UUID assetId, String objectKey,
            String sha256, long sizeBytes, String mime, Instant createdAt) {}
    public record Usage(long objectLimitBytes, long reservedBytes, long committedBytes, long workspaceQuotaBytes,
            long workspaceQuotaInodes, Long measuredBytes, Long measuredInodes, Instant measuredAt) {}
    public record Operation(UUID id, UUID spaceId, UUID workspaceId, UUID actorId, String kind, UUID uploadId,
            UUID commandId, UUID ownerId, long generation, Long fence, Instant leaseUntil, Instant deadlineAt,
            String status, String effectState, String errorCode, long revision) {}
    public record Publication(UUID id, UUID operationId, UUID spaceId, UUID workspaceId, long generation, UUID relayOwner) {}
    public record Lease(UUID workspaceId, UUID spaceId, UUID operationId, UUID ownerId, long fence, Instant leaseUntil, String status) {}
    public record Command(Operation operation, Upload upload, int projectId) {}
    public record Receipt(UUID commandId, String resultState, String resultText, String resultHash, String stopText,
            String stopHash, UUID observerRunnerId, UUID priorRunnerProofId) {}
}
