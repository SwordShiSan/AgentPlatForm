package com.agentplatform.trigger.http;

import com.agentplatform.api.capability.CapabilityBindingsApi;
import com.agentplatform.domain.chat.ChatFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("chat-local")
@ConditionalOnProperty(name = "platform.capabilities.enabled", havingValue = "true")
@RequestMapping("/api/v1")
public final class CapabilityBindingsController {
    private final CapabilityBindingsApi service;
    public CapabilityBindingsController(CapabilityBindingsApi service) { this.service = service; }

    public record Body(Boolean inheritPersonal, List<CapabilityBindingsApi.Selection> selections) {}

    @GetMapping("/me/capability-bindings")
    ResponseEntity<CapabilityBindingsApi.Bindings> personal(HttpServletRequest request) {
        return response(service.read(RequestIdentity.caller(request), null));
    }
    @GetMapping("/workspaces/{id}/capability-bindings")
    ResponseEntity<CapabilityBindingsApi.Bindings> workspace(HttpServletRequest request, @PathVariable("id") String id) {
        return response(service.read(RequestIdentity.caller(request), id));
    }
    @PutMapping("/me/capability-bindings")
    ResponseEntity<CapabilityBindingsApi.Bindings> replacePersonal(HttpServletRequest request, @RequestBody Body body) {
        return replace(request, null, body);
    }
    @PutMapping("/workspaces/{id}/capability-bindings")
    ResponseEntity<CapabilityBindingsApi.Bindings> replaceWorkspace(HttpServletRequest request,
            @PathVariable("id") String id, @RequestBody Body body) { return replace(request, id, body); }
    @GetMapping("/effective-capabilities")
    ResponseEntity<CapabilityBindingsApi.Effective> effective(HttpServletRequest request,
            @RequestParam(value = "workspaceId", required = false) String workspaceId) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(service.effective(RequestIdentity.caller(request), workspaceId));
    }

    private ResponseEntity<CapabilityBindingsApi.Bindings> replace(HttpServletRequest request, String workspace, Body body) {
        String match = request.getHeader("If-Match");
        if (match == null) throw new ChatFailure(428, "VERSION_REQUIRED");
        long revision;
        try {
            if (!match.matches("\"[0-9]+\"")) throw new NumberFormatException();
            revision = Long.parseLong(match.substring(1, match.length() - 1));
        } catch (NumberFormatException invalid) { throw new ChatFailure(400, "INVALID_VERSION"); }
        String key = request.getHeader("Idempotency-Key");
        if (key == null) throw new ChatFailure(428, "IDEMPOTENCY_KEY_REQUIRED");
        if (body == null || body.inheritPersonal() == null || body.selections() == null)
            throw new ChatFailure(400, "INVALID_CAPABILITY_BINDINGS");
        return response(service.replace(RequestIdentity.caller(request), workspace, revision,
                new CapabilityBindingsApi.Update(body.inheritPersonal(), body.selections()), key));
    }
    private static ResponseEntity<CapabilityBindingsApi.Bindings> response(CapabilityBindingsApi.Bindings result) {
        return ResponseEntity.ok().eTag("\"" + result.revision() + "\"").header("Cache-Control", "no-store").body(result);
    }
}
