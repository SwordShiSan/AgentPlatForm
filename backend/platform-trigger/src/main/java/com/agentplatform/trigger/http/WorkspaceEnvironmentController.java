package com.agentplatform.trigger.http;

import com.agentplatform.api.workspace.*;
import com.agentplatform.domain.workspace.WorkspaceFailure;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController @Profile("workspace-api")
@ConditionalOnProperty(name={"platform.workspace.enabled","platform.workspace.environment.enabled"},havingValue="true")
@RequestMapping("/api/v1/workspaces/{id}/environment")
public final class WorkspaceEnvironmentController {
    public record EmptyCommand() {}
    private final WorkspaceEnvironmentApi service;
    public WorkspaceEnvironmentController(WorkspaceEnvironmentApi service) {this.service=service;}
    private static WorkspaceApi.Caller caller(HttpServletRequest request) {
        var identity=RequestIdentity.caller(request);return new WorkspaceApi.Caller(identity.userId(),identity.spaceId());
    }
    private ResponseEntity<WorkspaceEnvironmentApi.Submission> submit(HttpServletRequest request,String workspace,EmptyCommand body,String command) {
        if(body==null&&(request.getContentLengthLong()>0||request.getHeader("Transfer-Encoding")!=null))throw new WorkspaceFailure(400,"EMPTY_ENVIRONMENT_COMMAND_REQUIRED");
        String key=request.getHeader("Idempotency-Key"),match=request.getHeader("If-Match");
        if(key==null)throw new WorkspaceFailure(428,"IDEMPOTENCY_KEY_REQUIRED");
        if(match==null)throw new WorkspaceFailure(428,"VERSION_REQUIRED");
        long version;
        try {
            if(!match.matches("\"(0|[1-9][0-9]*)\""))throw new NumberFormatException();
            version=Long.parseLong(match.substring(1,match.length()-1));
        } catch(NumberFormatException invalid) {throw new WorkspaceFailure(400,"INVALID_VERSION");}
        var result=switch(command) {
            case "PREPARE" -> service.prepare(caller(request),workspace,key,version,false);
            case "RETRY" -> service.prepare(caller(request),workspace,key,version,true);
            default -> service.transition(caller(request),workspace,key,version,WorkspaceEnvironmentApi.Action.valueOf(command));
        };
        return ResponseEntity.accepted().header("Cache-Control","no-store").eTag("\""+result.environment().version()+"\"").body(result);
    }
    @GetMapping ResponseEntity<WorkspaceEnvironmentApi.View> get(HttpServletRequest request,@PathVariable("id") String id) {
        var result=service.get(caller(request),id);
        return ResponseEntity.ok().header("Cache-Control","no-store").eTag("\""+result.version()+"\"").body(result);
    }
    @PostMapping ResponseEntity<WorkspaceEnvironmentApi.Submission> prepare(HttpServletRequest request,@PathVariable("id") String id,@RequestBody(required=false) EmptyCommand body) {
        return submit(request,id,body,"PREPARE");
    }
    @PostMapping("/retry") ResponseEntity<WorkspaceEnvironmentApi.Submission> retry(HttpServletRequest request,@PathVariable("id") String id,@RequestBody(required=false) EmptyCommand body) {
        return submit(request,id,body,"RETRY");
    }
    @PostMapping("/sleep") ResponseEntity<WorkspaceEnvironmentApi.Submission> sleep(HttpServletRequest request,@PathVariable("id") String id,@RequestBody(required=false) EmptyCommand body) {
        return submit(request,id,body,"SLEEP");
    }
    @PostMapping("/wake") ResponseEntity<WorkspaceEnvironmentApi.Submission> wake(HttpServletRequest request,@PathVariable("id") String id,@RequestBody(required=false) EmptyCommand body) {
        return submit(request,id,body,"WAKE");
    }
    @DeleteMapping ResponseEntity<WorkspaceEnvironmentApi.Submission> delete(HttpServletRequest request,@PathVariable("id") String id,@RequestBody(required=false) EmptyCommand body) {
        return submit(request,id,body,"DELETE");
    }
    @GetMapping("/operations/{operationId}") ResponseEntity<WorkspaceEnvironmentApi.OperationView> operation(HttpServletRequest request,@PathVariable("id") String id,@PathVariable("operationId") String operationId) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(service.operation(caller(request),id,operationId));
    }
    @GetMapping("/requests/{requestId}") ResponseEntity<WorkspaceEnvironmentApi.OperationView> original(HttpServletRequest request,@PathVariable("id") String id,@PathVariable("requestId") String requestId) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(service.request(caller(request),id,requestId));
    }
}
