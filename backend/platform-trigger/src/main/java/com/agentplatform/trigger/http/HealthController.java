package com.agentplatform.trigger.http;

import com.agentplatform.api.health.HealthQuery;
import com.agentplatform.api.health.HealthResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class HealthController {
    private final HealthQuery query;

    public HealthController(HealthQuery query) {
        this.query = query;
    }

    @GetMapping("/api/v1/health")
    public HealthResponse getHealth() {
        return query.getHealth();
    }
}
