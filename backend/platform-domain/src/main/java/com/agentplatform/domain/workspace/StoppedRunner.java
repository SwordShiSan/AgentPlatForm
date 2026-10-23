package com.agentplatform.domain.workspace;

import java.time.Instant;

/** Host evidence for one exact runner start; never evidence for sandbox termination. */
public record StoppedRunner(RunnerInstance instance,Instant stoppedAt,Instant observedAt,String evidenceHash) {
    public StoppedRunner {
        java.util.Objects.requireNonNull(instance);java.util.Objects.requireNonNull(stoppedAt);java.util.Objects.requireNonNull(observedAt);
        if(!Instant.parse(instance.startedAt()).isBefore(stoppedAt)||stoppedAt.isAfter(observedAt)
                ||evidenceHash==null||!evidenceHash.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid runner stop evidence");
    }
}
