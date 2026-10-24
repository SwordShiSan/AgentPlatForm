package com.agentplatform.infrastructure.dao.po;

import java.util.UUID;

public final class FileRunnerRows {
    private FileRunnerRows() {}
    public record Physical(UUID commandId,UUID runnerInstanceId,String requestHash,String imageId,String policyHash,
            String containerId,boolean startRequested,String startedAt,String execId) {}
}
