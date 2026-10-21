package com.agentplatform.trigger.http;

import com.agentplatform.api.workspace.RunnerControlApi;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController @Profile("workspace-runner")
@RequestMapping("/internal/operations")
public final class RunnerController {
    private final RunnerControlApi runner;
    public RunnerController(RunnerControlApi runner) { this.runner=runner; }
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<byte[]> provision(@AuthenticationPrincipal Jwt jwt,@RequestBody byte[] body,HttpServletRequest request) {
        if(request.getQueryString()!=null)throw new com.agentplatform.domain.workspace.WorkspaceFailure(400,"INVALID_RUNNER_ROUTE");
        return result(runner.provision(jwt.getTokenValue(),body));
    }
    @GetMapping("/{id}") ResponseEntity<byte[]> query(@AuthenticationPrincipal Jwt jwt,@PathVariable("id")String id,HttpServletRequest request) {
        if(request.getQueryString()!=null)throw new com.agentplatform.domain.workspace.WorkspaceFailure(400,"INVALID_RUNNER_ROUTE");
        return result(runner.query(jwt.getTokenValue(),id));
    }
    private static ResponseEntity<byte[]> result(RunnerControlApi.SignedResult result) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header("Cache-Control","no-store")
                .header("X-Runner-Signature",result.signature()).body(result.body());
    }
}
