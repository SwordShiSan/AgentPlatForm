package com.agentplatform.trigger.http;

import com.agentplatform.api.capability.McpConnectorApi;
import com.agentplatform.domain.files.FileFailure;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("workspace-api")
@ConditionalOnProperty(name = {"platform.capabilities.enabled", "platform.mcp.enabled"}, havingValue = "true")
@RequestMapping("/api/v1/connectors")
public final class McpConnectorController {
    private final McpConnectorApi service;
    public McpConnectorController(McpConnectorApi service) { this.service = service; }
    @GetMapping ResponseEntity<McpConnectorApi.Page> list(HttpServletRequest request,
            @RequestParam(value="cursor", required=false) String cursor, @RequestParam(value="limit", defaultValue="50") int limit) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.list(RequestIdentity.caller(request), cursor, limit));
    }
    @GetMapping("/{id}") ResponseEntity<McpConnectorApi.Configuration> get(HttpServletRequest request, @PathVariable("id") String id) {
        return configuration(200, service.get(RequestIdentity.caller(request), id));
    }
    @PostMapping ResponseEntity<McpConnectorApi.Configuration> create(HttpServletRequest request, @RequestBody McpConnectorApi.Input body) {
        return configuration(201, service.create(RequestIdentity.caller(request), key(request), body));
    }
    @PutMapping("/{id}") ResponseEntity<McpConnectorApi.Configuration> update(HttpServletRequest request,
            @PathVariable("id") String id, @RequestBody McpConnectorApi.Input body) {
        return configuration(200, service.update(RequestIdentity.caller(request), id, version(request), key(request), body));
    }
    @PostMapping("/{id}/test") ResponseEntity<McpConnectorApi.Probe> test(HttpServletRequest request, @PathVariable("id") String id) {
        return ResponseEntity.accepted().header("Cache-Control", "no-store")
                .body(service.test(RequestIdentity.caller(request), id, version(request), key(request)));
    }
    @GetMapping("/tests/{id}") ResponseEntity<McpConnectorApi.Probe> probe(HttpServletRequest request, @PathVariable("id") String id) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.probe(RequestIdentity.caller(request), id));
    }
    @GetMapping("/requests/{requestId}") ResponseEntity<McpConnectorApi.Configuration> request(HttpServletRequest request, @PathVariable("requestId") String requestId) {
        return configuration(200, service.byRequest(RequestIdentity.caller(request), requestId));
    }
    @GetMapping("/tests/requests/{requestId}") ResponseEntity<McpConnectorApi.Probe> probeRequest(HttpServletRequest request, @PathVariable("requestId") String requestId) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.probeByRequest(RequestIdentity.caller(request), requestId));
    }
    @GetMapping("/{id}/versions") ResponseEntity<McpConnectorApi.VersionPage> versions(HttpServletRequest request, @PathVariable("id") String id,
            @RequestParam(value="cursor", required=false) Long cursor, @RequestParam(value="limit", defaultValue="50") int limit) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.versions(RequestIdentity.caller(request), id, cursor, limit));
    }
    @GetMapping("/{id}/versions/{versionId}") ResponseEntity<McpConnectorApi.VersionDetail> version(HttpServletRequest request,
            @PathVariable("id") String id, @PathVariable("versionId") String versionId) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.version(RequestIdentity.caller(request), id, versionId));
    }
    @GetMapping("/{id}/activation") ResponseEntity<McpConnectorApi.Activation> activation(HttpServletRequest request, @PathVariable("id") String id) {
        return activation(service.activation(RequestIdentity.caller(request), id));
    }
    @PostMapping("/{id}/versions/{versionId}/enable") ResponseEntity<McpConnectorApi.Activation> enable(HttpServletRequest request,
            @PathVariable("id") String id, @PathVariable("versionId") String versionId) {
        return activation(service.enable(RequestIdentity.caller(request), id, versionId, version(request), key(request)));
    }
    @PostMapping("/{id}/disable") ResponseEntity<McpConnectorApi.Activation> disable(HttpServletRequest request, @PathVariable("id") String id) {
        return activation(service.disable(RequestIdentity.caller(request), id, version(request), key(request)));
    }
    private static ResponseEntity<McpConnectorApi.Activation> activation(McpConnectorApi.Activation value) {
        return ResponseEntity.ok().eTag("\"" + value.revision() + "\"").header("Cache-Control", "no-store").body(value);
    }
    private static ResponseEntity<McpConnectorApi.Configuration> configuration(int status, McpConnectorApi.Configuration value) {
        return ResponseEntity.status(status).eTag("\"" + value.revision() + "\"").header("Cache-Control", "no-store").body(value);
    }
    private static String key(HttpServletRequest request) {
        String value = request.getHeader("Idempotency-Key");
        if (value == null) throw new FileFailure(428, "IDEMPOTENCY_KEY_REQUIRED"); return value;
    }
    private static long version(HttpServletRequest request) {
        String value = request.getHeader("If-Match"); if (value == null) throw new FileFailure(428, "VERSION_REQUIRED");
        try { if (!value.matches("\"[0-9]+\"")) throw new NumberFormatException(); return Long.parseLong(value.substring(1, value.length()-1)); }
        catch (NumberFormatException invalid) { throw new FileFailure(400, "INVALID_VERSION"); }
    }
}
