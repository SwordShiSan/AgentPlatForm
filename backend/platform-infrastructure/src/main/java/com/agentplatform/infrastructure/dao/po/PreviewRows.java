package com.agentplatform.infrastructure.dao.po;

import java.util.UUID;

public final class PreviewRows {
    private PreviewRows() {}
    public record Source(UUID spaceId,UUID workspaceId,UUID fileId,UUID versionId,UUID assetId,String name,String objectKey,long bytes,String sha256) {}
    public record Request(String requestHash,UUID previewId,String kind) {}
    public record Heavy(String kind,UUID referenceId,Long generation,UUID ownerId,long fence) {}
    public record Attempt(UUID commandId,String commandJson,UUID runnerId,String status,String containerId,boolean startRequested,String startedAt,String execId,String receiptJson) {}
    public record Reservation(long bytes,String status) {}
}
