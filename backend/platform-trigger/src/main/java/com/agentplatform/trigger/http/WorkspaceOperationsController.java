package com.agentplatform.trigger.http;

import com.agentplatform.api.chat.WorkspaceOperationsApi;
import com.agentplatform.types.WorkspaceOperationData.Page;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController @Profile("chat-local")
@ConditionalOnProperty(name="platform.workspace.tools.enabled",havingValue="true")
public final class WorkspaceOperationsController {
    private final WorkspaceOperationsApi operations;
    public WorkspaceOperationsController(WorkspaceOperationsApi operations){this.operations=operations;}
    @GetMapping("/api/v1/operations/{id}/result")
    com.agentplatform.types.WorkspaceOperationData.Result result(@PathVariable("id")String operation,HttpServletRequest request) {
        return operations.result(RequestIdentity.caller(request),operation);
    }
    @GetMapping("/api/v1/tasks/{id}/operations")
    Page list(@PathVariable("id")String task,@RequestParam(name="cursor",required=false)String cursor,
              @RequestParam(name="limit",defaultValue="20")int limit,HttpServletRequest request) {
        return operations.list(RequestIdentity.caller(request),task,cursor,limit);
    }
}
