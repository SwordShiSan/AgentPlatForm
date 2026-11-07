package com.agentplatform.trigger.http;

import com.agentplatform.api.capability.SkillVersionApi;
import com.agentplatform.domain.files.FileFailure;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("workspace-api")
@ConditionalOnProperty(name = "platform.capabilities.enabled", havingValue = "true")
@RequestMapping("/api/v1/skills/{id}/versions/{versionId}")
public final class SkillVersionController {
    private final SkillVersionApi service;
    public SkillVersionController(SkillVersionApi service) { this.service = service; }
    @GetMapping ResponseEntity<SkillVersionApi.View> get(HttpServletRequest request, @PathVariable("id") String id,
                                                        @PathVariable("versionId") String versionId) {
        return response(service.get(RequestIdentity.caller(request), id, versionId));
    }
    @PostMapping("/publish") ResponseEntity<SkillVersionApi.View> publish(HttpServletRequest request,
            @PathVariable("id") String id, @PathVariable("versionId") String versionId) {
        return change(request, id, versionId, "PUBLISH");
    }
    @PostMapping("/revoke") ResponseEntity<SkillVersionApi.View> revoke(HttpServletRequest request,
            @PathVariable("id") String id, @PathVariable("versionId") String versionId) {
        return change(request, id, versionId, "REVOKE");
    }
    private ResponseEntity<SkillVersionApi.View> change(HttpServletRequest request, String id, String versionId, String action) {
        String match = request.getHeader("If-Match");
        if (match == null) throw new FileFailure(428, "VERSION_REQUIRED");
        long expected;
        try {
            if (!match.matches("\"[0-9]+\"")) throw new NumberFormatException();
            expected = Long.parseLong(match.substring(1, match.length() - 1));
        } catch (NumberFormatException invalid) { throw new FileFailure(400, "INVALID_VERSION"); }
        String key = request.getHeader("Idempotency-Key");
        if (key == null) throw new FileFailure(428, "IDEMPOTENCY_KEY_REQUIRED");
        return response(service.change(RequestIdentity.caller(request), id, versionId, action, expected, key));
    }
    private static ResponseEntity<SkillVersionApi.View> response(SkillVersionApi.View value) {
        return ResponseEntity.ok().eTag("\"" + value.revision() + "\"").header("Cache-Control", "no-store").body(value);
    }
}
