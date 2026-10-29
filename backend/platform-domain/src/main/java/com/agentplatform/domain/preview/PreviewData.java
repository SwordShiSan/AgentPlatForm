package com.agentplatform.domain.preview;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Durable preview facts are independent of mutable workspace files and task execution. */
public final class PreviewData {
    private PreviewData() {}
    public static final long MAX_BYTES=104857600L;
    public record Job(UUID id,UUID spaceId,UUID workspaceId,UUID actorId,UUID fileId,UUID versionId,String name,
            String requestedFormat,String sourceFormat,String sourceObjectKey,long sourceBytes,String sourceSha256,
            String imageId,String bundle,String policyHash,String state,long revision,long generation,UUID commandId,
            UUID ownerId,Long fence,Instant leaseUntil,Instant deadlineAt,int attempts,String format,String outputObjectKey,
            Long bytes,String sha256,String mediaType,Integer pages,String errorCode,boolean retryable,UUID assetId) {}
    public record Publication(UUID id,UUID previewId,UUID spaceId,long generation,UUID relayOwner) {}
    /** Frozen once at durable claim; mutable lease/projection fields are deliberately absent. */
    public record Command(UUID commandId,UUID previewId,UUID spaceId,UUID workspaceId,UUID actorId,UUID fileId,UUID versionId,
            String name,String sourceFormat,String requestedFormat,String sourceObjectKey,long sourceBytes,String sourceSha256,
            String imageId,String bundle,String policyHash,long generation,UUID ownerId,long fence,Instant deadlineAt,String outputObjectKey) {
        public static Command freeze(Job row) {
            return new Command(row.commandId(),row.id(),row.spaceId(),row.workspaceId(),row.actorId(),row.fileId(),row.versionId(),row.name(),
                    row.sourceFormat(),row.requestedFormat(),row.sourceObjectKey(),row.sourceBytes(),row.sourceSha256(),row.imageId(),
                    row.bundle(),row.policyHash(),row.generation(),row.ownerId(),row.fence(),row.deadlineAt(),row.outputObjectKey());
        }
    }
    public record Stop(String containerId,String execId,String imageId,Instant startedAt,boolean containerStopped,boolean execStopped,boolean callbacksStopped,Instant finishedAt) {
        public boolean confirmed(){return containerStopped&&execStopped&&callbacksStopped&&finishedAt!=null;}
    }
    public record Receipt(UUID commandId,UUID previewId,long generation,long fence,UUID runnerId,String state,String format,
            String mediaType,long bytes,String sha256,Integer pages,String objectKey,boolean sourceReferenced,String errorCode,
            boolean retryable,Stop stop) {}
    public static List<String> actions(Job row) {
        return "READY".equals(row.state())?List.of("VIEW","DOWNLOAD_SOURCE"):
                "FAILED".equals(row.state())&&row.retryable()?List.of("RETRY","DOWNLOAD_SOURCE"):List.of("QUERY","DOWNLOAD_SOURCE");
    }
}
