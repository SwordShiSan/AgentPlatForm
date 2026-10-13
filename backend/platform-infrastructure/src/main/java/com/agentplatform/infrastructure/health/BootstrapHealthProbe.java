package com.agentplatform.infrastructure.health;

import com.agentplatform.domain.health.HealthProbe;
import com.agentplatform.types.health.HealthStatus;

public final class BootstrapHealthProbe implements HealthProbe {
    @Override
    public HealthStatus status() {
        return HealthStatus.UP;
    }
}
