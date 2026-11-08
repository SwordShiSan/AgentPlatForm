package com.agentplatform.trigger.http;

import com.agentplatform.api.capability.SkillCatalogApi;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("workspace-api")
@ConditionalOnProperty(name = "platform.capabilities.enabled", havingValue = "true")
@RequestMapping("/api/v1/skills")
public final class SkillCatalogController {
    private final SkillCatalogApi service;
    public SkillCatalogController(SkillCatalogApi service) { this.service = service; }
    @GetMapping ResponseEntity<SkillCatalogApi.Page> skills(HttpServletRequest request,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.skills(RequestIdentity.caller(request), cursor, limit));
    }
    @GetMapping("/{id}/versions") ResponseEntity<SkillCatalogApi.Page> versions(HttpServletRequest request,
            @PathVariable("id") String id, @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.versions(RequestIdentity.caller(request), id, cursor, limit));
    }
}
