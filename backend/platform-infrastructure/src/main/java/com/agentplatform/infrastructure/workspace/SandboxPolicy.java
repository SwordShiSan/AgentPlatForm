package com.agentplatform.infrastructure.workspace;

import java.nio.file.Path;
import java.util.UUID;

/** Operator-only immutable inputs. Never populated from an HTTP request. */
public record SandboxPolicy(String image, String helperImage, String workspaceRoot, UUID mountUuid,
                            String quotaDevice, Path runtimeAttestation) {
    public static final long MEMORY = 512L * 1024 * 1024;
    public static final int OUTPUT_BYTES = 65536;
    public static final int GUEST_NPROC = 32;
    public static final String RUNSC_PATH = "/usr/local/lib/agentplatform-gvisor/20261005.0/runsc";
    public static final String RUNSC_SHA256 = "210b437a9cfae51e8f8c9074ed19b8b5e59477178e2e391a18117d8d6b924f7a";

    public SandboxPolicy {
        if (image == null || !image.matches("sha256:[a-f0-9]{64}")
                || helperImage == null || !helperImage.matches("sha256:[a-f0-9]{64}")
                || workspaceRoot == null || !(workspaceRoot.equals("/srv/agentplatform/workspaces")
                || workspaceRoot.matches("/srv/agentplatform/acceptance/r32-[a-z0-9-]+/workspaces"))) {
            throw new IllegalArgumentException("Pinned sandbox images and trusted workspace root required");
        }
        java.util.Objects.requireNonNull(mountUuid);
        java.util.Objects.requireNonNull(runtimeAttestation);
        if (DockerWorkspaceAllocator.fixedQuotaDevice(quotaDevice) == null) {
            throw new IllegalArgumentException("Verified quota device required");
        }
    }

    public String hash() {
        return RunnerSignatures.sha256(("environment-v2|" + image + "|" + helperImage + "|" + workspaceRoot + "|" + mountUuid + "|" + quotaDevice
                + "|" + RUNSC_SHA256 + "|systrap|uid10001|ro|net-none|caps-none|devices-none|"
                + "memory512m-swap512m|cpu1|pids128|nproc" + GUEST_NPROC + "|tmpfs64m|bridge-v2-jsonl|startup-canary-v1").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static boolean guestProcessLimitMatches(com.github.dockerjava.api.model.Ulimit[] limits) {
        return limits != null && limits.length == 1 && limits[0] != null
                && "nproc".equals(limits[0].getName())
                && Long.valueOf(GUEST_NPROC).equals(limits[0].getSoftLong())
                && Long.valueOf(GUEST_NPROC).equals(limits[0].getHardLong());
    }

    public String work(com.agentplatform.domain.workspace.SandboxRuntime.Identity identity) {
        return workspaceRoot + "/" + identity.spaceId() + "/" + identity.workspaceId() + "/work";
    }
}
