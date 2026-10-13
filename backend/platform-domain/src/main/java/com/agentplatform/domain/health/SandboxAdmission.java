package com.agentplatform.domain.health;

/** Minimal-host admission; permission/lease/runner checks remain mandatory elsewhere. */
public final class SandboxAdmission {
    public enum Result { ALLOW, QUEUED, DISABLED }
    public static final long MIB = 1024L * 1024;
    private SandboxAdmission() {}

    public static Result decide(boolean runtimeVerified, boolean quotaVerified, int active,
            long memoryAvailableBytes, long rootAvailableBytes) {
        if (active < 0 || memoryAvailableBytes < 0 || rootAvailableBytes < 0) throw new IllegalArgumentException("Invalid host observation");
        if (!runtimeVerified || !quotaVerified) return Result.DISABLED;
        // 512MiB sandbox +128MiB runtime allowance +512MiB free safety floor.
        if (active >= 1 || memoryAvailableBytes < 1152 * MIB || rootAvailableBytes < 5L * 1024 * MIB) return Result.QUEUED;
        return Result.ALLOW;
    }
}
