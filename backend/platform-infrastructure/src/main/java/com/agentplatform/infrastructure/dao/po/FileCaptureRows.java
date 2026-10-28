package com.agentplatform.infrastructure.dao.po;

import java.util.UUID;

public final class FileCaptureRows {
    private FileCaptureRows() {}
    public record Reservation(UUID id,UUID operationId,UUID spaceId,UUID workspaceId,String path,long bytes,
                              UUID versionId,UUID assetId,String objectKey,UUID fileOperationId) {}
    public record Projection(UUID operationId,UUID spaceId,UUID workspaceId,UUID actorId,UUID fileId,UUID parentVersionId,
                             UUID versionId,UUID assetId,long bytes,String sha256) {}
}
