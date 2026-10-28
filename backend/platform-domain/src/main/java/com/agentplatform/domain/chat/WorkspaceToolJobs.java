package com.agentplatform.domain.chat;

import java.util.List;
import java.util.UUID;

/** Private child-job links and result settlement. Methods require a short application transaction. */
public interface WorkspaceToolJobs {
    record Environment(String state,long version,String imageId,String policyHash) {}
    record Artifact(UUID fileId,UUID versionId,String path,long bytes,String sha256,String status) {}
    void lockQuota(UUID user,UUID space);
    Environment environment(UUID space,UUID workspace);
    void bindActivation(UUID operation,UUID activation);
    /** Observation of a proven stopped source; invalidates stale file projections once only. */
    void observeStoppedSource(UUID operation,UUID use);
    List<Artifact> artifacts(UUID operation);
    void settleResult(UUID operation,String objectKey,String sha256,int stdoutBytes,int stderrBytes);
    boolean childrenStopped(UUID operation);
}
