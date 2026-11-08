package com.agentplatform.trigger.http;

import com.agentplatform.api.knowledge.PersonalMemoryApi;
import com.agentplatform.domain.files.FileFailure;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @Profile("workspace-api") @RequestMapping("/api/v1/me/memories")
@ConditionalOnProperty(name="platform.knowledge.enabled",havingValue="true")
public final class PersonalMemoryController {
    private final PersonalMemoryApi service;
    public PersonalMemoryController(PersonalMemoryApi service){this.service=service;}
    @PostMapping("/candidates") ResponseEntity<PersonalMemoryApi.View> candidate(HttpServletRequest r,@RequestBody PersonalMemoryApi.Candidate body){
        return ResponseEntity.status(201).header("Cache-Control","no-store").body(service.candidate(caller(r),request(r),body));
    }
    @GetMapping ResponseEntity<?> list(HttpServletRequest r,@RequestParam(required=false)String status,@RequestParam(required=false)String workspaceId){
        return ResponseEntity.ok().header("Cache-Control","no-store").body(service.list(caller(r),status,workspaceId));
    }
    @PostMapping("/{id}/actions") ResponseEntity<PersonalMemoryApi.View> change(HttpServletRequest r,@PathVariable String id,@RequestBody PersonalMemoryApi.Change body){
        return ResponseEntity.ok().header("Cache-Control","no-store").body(service.change(caller(r),id,request(r),body));
    }
    private static PersonalMemoryApi.Caller caller(HttpServletRequest r){var c=RequestIdentity.caller(r);return new PersonalMemoryApi.Caller(c.userId(),c.spaceId());}
    private static String request(HttpServletRequest r){String key=r.getHeader("Idempotency-Key");if(key==null)throw new FileFailure(428,"IDEMPOTENCY_KEY_REQUIRED");return key;}
}
