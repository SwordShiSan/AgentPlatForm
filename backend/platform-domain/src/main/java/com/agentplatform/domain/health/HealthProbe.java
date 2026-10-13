package com.agentplatform.domain.health;

import com.agentplatform.types.health.HealthStatus;

/** Application-shell availability, not dependency or model readiness. */
public interface HealthProbe {
    HealthStatus status();
}
