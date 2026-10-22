package com.agentplatform.domain.workspace;

import java.time.Instant;
import java.util.UUID;

/** One trusted JVM within one exact Docker container start. Never a task identity. */
public record RunnerInstance(UUID id,String containerId,String imageId,String startedAt) {
    public RunnerInstance {
        java.util.Objects.requireNonNull(id);java.util.Objects.requireNonNull(startedAt);
        if(containerId==null||!containerId.matches("[0-9a-f]{64}")||imageId==null||!imageId.matches("sha256:[0-9a-f]{64}"))
            throw new IllegalArgumentException("Exact runner container identity required");
        Instant.parse(startedAt);
    }
}
