package com.agentplatform.trigger.http;

import com.agentplatform.api.workspace.WorkspaceApi;
import com.agentplatform.domain.workspace.WorkspaceFailure;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("workspace-api")
@ConditionalOnProperty(name="platform.workspace.enabled", havingValue="true")
@RequestMapping("/api/v1/workspaces")
public final class WorkspaceController {
    public record Create(@NotBlank @Size(max=160) String name) {}
    private final WorkspaceApi service;
    public WorkspaceController(WorkspaceApi service) { this.service=service; }
    private static WorkspaceApi.Caller caller(HttpServletRequest request) {
        var actor=RequestIdentity.caller(request);return new WorkspaceApi.Caller(actor.userId(),actor.spaceId());
    }
    private static String key(HttpServletRequest request) {
        String key=request.getHeader("Idempotency-Key");
        if(key==null)throw new WorkspaceFailure(428,"IDEMPOTENCY_KEY_REQUIRED");return key;
    }
    @PostMapping ResponseEntity<WorkspaceApi.View> create(HttpServletRequest request,@Valid @RequestBody Create body) {
        return accepted(service.create(caller(request),key(request),body.name()));
    }
    @GetMapping WorkspaceApi.Page list(HttpServletRequest request,
            @RequestParam(name="cursor",required=false)String cursor,@RequestParam(name="limit",defaultValue="20")int limit) {
        return service.list(caller(request),cursor,limit);
    }
    @GetMapping("/{id}") ResponseEntity<WorkspaceApi.View> get(HttpServletRequest request,@PathVariable("id")String id) {
        var view=service.get(caller(request),id);return ResponseEntity.ok().eTag("\""+view.version()+"\"").header("Cache-Control","no-store").body(view);
    }
    @PostMapping("/{id}/retry") ResponseEntity<WorkspaceApi.View> retry(HttpServletRequest request,@PathVariable("id")String id) {
        String match=request.getHeader("If-Match");if(match==null)throw new WorkspaceFailure(428,"VERSION_REQUIRED");
        try {
            if(!match.matches("\"[1-9][0-9]*\""))throw new NumberFormatException();
            long version=Long.parseLong(match.substring(1,match.length()-1));
            return accepted(service.retry(caller(request),id,key(request),version));
        } catch(NumberFormatException invalid){throw new WorkspaceFailure(400,"INVALID_VERSION");}
    }
    private static ResponseEntity<WorkspaceApi.View> accepted(WorkspaceApi.View view) {
        return ResponseEntity.accepted().eTag("\""+view.version()+"\"").header("Cache-Control","no-store").body(view);
    }
}
