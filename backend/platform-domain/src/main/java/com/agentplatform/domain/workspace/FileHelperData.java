package com.agentplatform.domain.workspace;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Separate trusted physical execution. A sandbox identity cannot stand in for it. */
public final class FileHelperData {
    private FileHelperData() {}
    private static void digest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Exact digest required");
    }
    public record Intent(UUID useId, String imageId, String policyHash, String containerName) {
        public Intent {
            Objects.requireNonNull(useId);
            if (imageId == null || !imageId.matches("sha256:[0-9a-f]{64}")) throw new IllegalArgumentException("Pinned helper image required");
            digest(policyHash);
            if (!Objects.equals(containerName, "ap-file-helper-" + useId)) throw new IllegalArgumentException("Use-derived helper name required");
        }
    }
    public record Physical(String containerId, String imageId, String startedAt, String execId) {
        public Physical {
            digest(containerId);
            if (imageId == null || !imageId.matches("sha256:[0-9a-f]{64}")) throw new IllegalArgumentException("Pinned helper image required");
            if (startedAt != null) Instant.parse(startedAt);
            if (execId != null) digest(execId);
            if (execId != null && startedAt == null) throw new IllegalArgumentException("Exec requires a started helper");
        }
    }
    public record Stopped(Physical physical, String imageId, boolean neverStarted, String finishedAt,
                          boolean execStopped, boolean callbacksStopped, Instant observedAt) {
        public Stopped {
            Objects.requireNonNull(observedAt);
            if (imageId == null || !imageId.matches("sha256:[0-9a-f]{64}")) throw new IllegalArgumentException("Pinned helper image required");
            if (!execStopped || !callbacksStopped) throw new IllegalArgumentException("Termination must be confirmed");
            if (neverStarted) {
                if (physical != null && (physical.startedAt() != null || physical.execId() != null)
                        || finishedAt != null || physical != null && !imageId.equals(physical.imageId()))
                    throw new IllegalArgumentException("Never-started facts cannot claim executed timestamps");
            } else {
                if (physical == null || physical.startedAt() == null || finishedAt == null || !imageId.equals(physical.imageId()))
                    throw new IllegalArgumentException("Actual lifecycle required");
                var finished = Instant.parse(finishedAt);
                if (!Instant.parse(physical.startedAt()).isBefore(finished) || finished.isAfter(observedAt))
                    throw new IllegalArgumentException("Invalid helper stop chronology");
            }
        }
    }
    public record Snapshot(Intent intent, UUID originalRunnerId, Physical physical, boolean startRequested, Stopped stopped) {
        public Snapshot { Objects.requireNonNull(intent);Objects.requireNonNull(originalRunnerId); }
    }
}
