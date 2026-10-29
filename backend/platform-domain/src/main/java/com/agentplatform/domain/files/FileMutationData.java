package com.agentplatform.domain.files;

import java.util.UUID;

/** Frozen lifecycle input; no mutable status, host path or credential. */
public final class FileMutationData {
    private FileMutationData() {}
    public record Input(String kind,UUID rootFileId,String sourcePath,String targetPath,String manifestJson,
            String manifestHash,UUID parentOperationId) {}
    public record Item(UUID fileId,String sourcePath,String targetPath,String kind,long expectedRevision,
            UUID versionId,String sha256,Long sizeBytes) {}
    public record Intent(UUID operationId,UUID spaceId,UUID workspaceId,UUID actorId,UUID requestId,String requestHash,
            String kind,UUID rootFileId,String sourcePath,String targetPath,long expectedRevision,
            String manifestJson,String manifestHash,int entryCount,UUID parentOperationId,UUID targetParentId,Long targetParentRevision) {}
    public record Restore(UUID uploadId,UUID operationId,UUID spaceId,UUID workspaceId,UUID fileId,UUID sourceVersionId,
            String sourcePath,boolean sourceMissing,String manifestJson,String manifestHash,UUID targetParentId,Long targetParentRevision) {}
    public record Request(UUID operationId,UUID uploadId,String state) {}
    public record Preview(long revision,int entries,boolean allConfirmed) {}
    public record Deleted(UUID id,UUID workspaceId,String path,String kind,UUID versionId,long revision,
            UUID deletionOperationId,java.time.Instant deletedAt,java.time.Instant retainUntil,boolean canRestore) {}
    public record TreeFile(UUID operationId,UUID spaceId,UUID workspaceId,UUID fileId,String kind,String sourcePath,String targetPath,
            long expectedRevision,UUID parentVersionId,UUID sourceVersionId,UUID versionId,UUID assetId,String objectKey,
            String sourceObjectKey,Long sizeBytes,String sha256) {}
}
