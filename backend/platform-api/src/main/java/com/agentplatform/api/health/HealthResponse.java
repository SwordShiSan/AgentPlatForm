package com.agentplatform.api.health;

import com.agentplatform.types.health.HealthStatus;

public record HealthResponse(HealthStatus status) {
}
