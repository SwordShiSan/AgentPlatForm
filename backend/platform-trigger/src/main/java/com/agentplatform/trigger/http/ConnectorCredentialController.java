package com.agentplatform.trigger.http;

import com.agentplatform.api.capability.ConnectorCredentialApi;
import com.agentplatform.domain.files.FileFailure;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("workspace-api")
@ConditionalOnProperty(name = {"platform.capabilities.enabled", "platform.mcp.enabled"}, havingValue = "true")
@RequestMapping("/api/v1/me/connector-credentials")
public final class ConnectorCredentialController {
    private final ConnectorCredentialApi service;
    public ConnectorCredentialController(ConnectorCredentialApi service) { this.service = service; }
    public record SecretBody(String secret) { @Override public String toString() { return "SecretBody[redacted]"; } }
    @GetMapping ResponseEntity<ConnectorCredentialApi.Page> list(HttpServletRequest request,
            @RequestParam(value="cursor", required=false) String cursor, @RequestParam(value="limit", defaultValue="50") int limit) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.list(RequestIdentity.caller(request), cursor, limit));
    }
    @PostMapping ResponseEntity<ConnectorCredentialApi.Metadata> create(HttpServletRequest request, @RequestBody SecretBody body) {
        if (body == null) throw new FileFailure(400, "CONNECTOR_CREDENTIAL_INVALID");
        return response(201, service.create(RequestIdentity.caller(request), key(request), body.secret()));
    }
    @GetMapping("/requests/{requestId}") ResponseEntity<ConnectorCredentialApi.Metadata> byRequest(HttpServletRequest request,
            @PathVariable("requestId") String requestId) {
        return response(200, service.byRequest(RequestIdentity.caller(request), requestId));
    }
    @PostMapping("/{id}/replace") ResponseEntity<ConnectorCredentialApi.Metadata> replace(HttpServletRequest request,
            @PathVariable("id") String id, @RequestBody SecretBody body) {
        if (body == null) throw new FileFailure(400, "CONNECTOR_CREDENTIAL_INVALID");
        return response(201, service.replace(RequestIdentity.caller(request), id, version(request), key(request), body.secret()));
    }
    @DeleteMapping("/{id}") ResponseEntity<ConnectorCredentialApi.Metadata> revoke(HttpServletRequest request, @PathVariable("id") String id) {
        return response(200, service.revoke(RequestIdentity.caller(request), id, version(request), key(request)));
    }
    private static ResponseEntity<ConnectorCredentialApi.Metadata> response(int status, ConnectorCredentialApi.Metadata value) {
        return ResponseEntity.status(status).eTag("\"" + value.revision() + "\"").header("Cache-Control", "no-store").body(value);
    }
    private static String key(HttpServletRequest request) {
        String value = request.getHeader("Idempotency-Key");
        if (value == null) throw new FileFailure(428, "IDEMPOTENCY_KEY_REQUIRED"); return value;
    }
    private static long version(HttpServletRequest request) {
        String value = request.getHeader("If-Match");
        if (value == null) throw new FileFailure(428, "VERSION_REQUIRED");
        try { if (!value.matches("\"[0-9]+\"")) throw new NumberFormatException(); return Long.parseLong(value.substring(1, value.length()-1)); }
        catch (NumberFormatException invalid) { throw new FileFailure(400, "INVALID_VERSION"); }
    }
}
