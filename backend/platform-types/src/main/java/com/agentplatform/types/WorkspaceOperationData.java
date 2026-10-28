package com.agentplatform.types;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Public, allowlisted metadata; never a generic operation or SDK-state projection. */
public final class WorkspaceOperationData {
    private WorkspaceOperationData() {}
    public record Output(String path,long maxBytes) {}
    public record Review(String kind,String command,String cwd,String path,UUID fileId,UUID versionId,
                         String writeMode,String textSha256,Long bytes,List<Output> outputs,String impact,
                         String network,boolean stopsAfterEachCall) {}
    public record Operation(UUID operationId,UUID taskId,String toolName,String status,String phase,String summary,
                            Instant createdAt,Instant endedAt,String errorCode,String traceId,Review review,boolean resultAvailable) {}
    public record Page(List<Operation> items,UUID nextCursor) {}
    public record Artifact(UUID fileId,UUID versionId,String path,long bytes,String sha256) {}
    public record Result(UUID operationId,UUID taskId,String status,String summary,Long exitCode,String stdout,String stderr,
                         boolean truncated,String errorCode,List<Artifact> files) {}
}
