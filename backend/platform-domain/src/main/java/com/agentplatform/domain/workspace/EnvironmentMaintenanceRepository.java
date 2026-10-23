package com.agentplatform.domain.workspace;

/** System-only, privilege-reducing maintenance. Expiry never transfers execution authority. */
public interface EnvironmentMaintenanceRepository {
    int blockExpired();
    int scheduleStop();
}
