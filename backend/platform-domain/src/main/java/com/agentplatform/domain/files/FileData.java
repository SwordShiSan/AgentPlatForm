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
    public record HistoricalVersion(UUID id, UUID fileId, String sha256, long sizeBytes, Instant createdAt, UUID sourceVersionId) {}
    public record Artifact(UUID id,UUID spaceId,UUID workspaceId,UUID taskId,UUID operationId,UUID fileId,
            UUID versionId,String title,String sha256,long sizeBytes,String evidenceStatus) {}
    public record Operation(UUID id, UUID spaceId, UUID workspaceId, UUID actorId, String kind, UUID uploadId,
            UUID commandId, UUID ownerId, long generation, Long fence, Instant leaseUntil, Instant deadlineAt,
            String status, String effectState, String errorCode, long revision, String helperImageId, String helperPolicyHash,
            String scanParent,String scanCursor,UUID scanEpoch,String scanStamp) {}
    public record Publication(UUID id, UUID operationId, UUID spaceId, UUID workspaceId, long generation, UUID relayOwner) {}
    public record Lease(UUID workspaceId, UUID spaceId, UUID operationId, UUID ownerId, long fence, Instant leaseUntil, String status) {}
    public record Execution(UUID id,UUID spaceId,UUID workspaceId,UUID actorId,String kind,UUID uploadId,
            UUID commandId,UUID ownerId,long generation,Long fence,Instant deadlineAt,String helperImageId,String helperPolicyHash,
            String scanParent,String scanCursor,UUID scanEpoch,String scanStamp) {
        public static Execution freeze(Operation row) {
            return new Execution(row.id(),row.spaceId(),row.workspaceId(),row.actorId(),row.kind(),row.uploadId(),row.commandId(),
                    row.ownerId(),row.generation(),row.fence(),row.deadlineAt(),row.helperImageId(),row.helperPolicyHash(),row.scanParent(),row.scanCursor(),row.scanEpoch(),row.scanStamp());
        }
    }
    public record Input(UUID id,UUID spaceId,UUID workspaceId,UUID actorId,String relativePath,String mode,UUID fileId,
            UUID baseVersionId,Long expectedRevision,UUID versionId,UUID assetId,String objectKey,long sizeBytes,String sha256) {
        public static Input freeze(Upload row) {
            return new Input(row.id(),row.spaceId(),row.workspaceId(),row.actorId(),row.relativePath(),row.mode(),row.fileId(),
                    row.baseVersionId(),row.expectedRevision(),row.versionId(),row.assetId(),row.objectKey(),row.sizeBytes(),row.sha256());
        }
    }
    /** Immutable physical command, deliberately excluding changing lease/status/revision projections. */
    public record Command(Execution operation, Input upload, int projectId, String baseSha256,
            FileMutationData.Input mutation) {
        public Command(Execution operation,Input upload,int projectId,String baseSha256) {this(operation,upload,projectId,baseSha256,null);}
    }
    public record Receipt(UUID commandId, String resultState, String resultText, String resultHash, String stopText,
            String stopHash, UUID observerRunnerId, UUID priorRunnerProofId) {}
    public record ScanItem(String path,String kind,Long bytes,String sha256) {}
    public record ScanPage(String protocol,UUID operationId,String state,String parent,UUID epoch,String stamp,
            String cursor,String next,boolean restart,java.util.List<ScanItem> entries) {}
    public record ScanDirectory(UUID spaceId,UUID workspaceId,UUID actorId,String parentPath,String cursor,UUID epoch,String stamp) {}
}
