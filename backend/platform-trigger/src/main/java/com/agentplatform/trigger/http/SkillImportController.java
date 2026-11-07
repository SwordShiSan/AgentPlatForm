package com.agentplatform.trigger.http;

import com.agentplatform.api.capability.SkillImportApi;
import com.agentplatform.domain.files.FileFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("workspace-api")
@ConditionalOnProperty(name = "platform.capabilities.enabled", havingValue = "true")
@RequestMapping("/api/v1/skills/imports")
public final class SkillImportController {
    private final SkillImportApi service;
    public SkillImportController(SkillImportApi service) { this.service = service; }
    public record CreateBody(String capabilityId, Long sizeBytes, String sha256) {}
    @PostMapping ResponseEntity<SkillImportApi.View> create(HttpServletRequest request, @RequestBody CreateBody body) {
        String key = request.getHeader("Idempotency-Key");
        if (key == null) throw new FileFailure(428, "IDEMPOTENCY_KEY_REQUIRED");
        if (body == null || body.sizeBytes() == null) throw new FileFailure(400, "INVALID_SKILL_ARCHIVE");
        return ResponseEntity.status(201).header("Cache-Control", "no-store").body(service.create(
                RequestIdentity.caller(request), key, new SkillImportApi.Create(body.capabilityId(), body.sizeBytes(), body.sha256())));
    }
    @GetMapping("/{id}") ResponseEntity<SkillImportApi.View> get(HttpServletRequest request, @PathVariable("id") String id) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.get(RequestIdentity.caller(request), id));
    }
    @PutMapping(value = "/{id}/content", consumes = "application/octet-stream")
    ResponseEntity<SkillImportApi.View> transfer(HttpServletRequest request, @PathVariable("id") String id) throws IOException {
        long length = request.getContentLengthLong();
        if (length < 0) throw new FileFailure(411, "CONTENT_LENGTH_REQUIRED");
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(service.transfer(RequestIdentity.caller(request), id, length, request.getInputStream()));
    }
    @PostMapping("/{id}/complete") ResponseEntity<SkillImportApi.View> complete(HttpServletRequest request, @PathVariable("id") String id) {
        String key = request.getHeader("Idempotency-Key");
        if (key == null) throw new FileFailure(428, "IDEMPOTENCY_KEY_REQUIRED");
        return ResponseEntity.accepted().header("Cache-Control", "no-store").body(service.complete(RequestIdentity.caller(request), id, key));
    }
}
