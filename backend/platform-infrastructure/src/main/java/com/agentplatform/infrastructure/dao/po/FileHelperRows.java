package com.agentplatform.infrastructure.dao.po;

import java.time.Instant;
import java.util.UUID;

public final class FileHelperRows {
    private FileHelperRows() {}
    public record Helper(UUID useId, UUID spaceId, UUID workspaceId, UUID environmentId, UUID ownerId,
            long generation, long fence, UUID runnerInstanceId, String imageId, String policyHash, String containerName,
            String intentHash, String containerId, boolean startRequested, String startedAt, String execId, long version) {}
    public record Stop(String stopText, String contentHash, UUID observerRunnerId, UUID priorRunnerProofId,
            String containerId, String imageId, String startedAt, String execId, boolean neverStarted, String finishedAt,
            boolean execStopped, boolean callbacksStopped, Instant observedAt) {}
}
