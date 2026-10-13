package com.agentplatform.trigger.http;

import com.agentplatform.api.health.ReadinessQuery;
import com.agentplatform.api.health.ReadinessResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class ReadinessController {
    private final ReadinessQuery query;
    public ReadinessController(ReadinessQuery query) { this.query = query; }

    @GetMapping("/api/v1/readiness")
    public ResponseEntity<ReadinessResponse> getReadiness() {
        boolean ready = query.ready();
        return ResponseEntity.status(ready ? 200 : 503).body(new ReadinessResponse(ready ? "READY" : "NOT_READY"));
    }
}
