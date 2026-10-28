package com.agentplatform.trigger.http;

import com.agentplatform.api.files.FileCaptureRunnerApi;
import com.agentplatform.api.workspace.RunnerControlApi.SignedResult;
import com.agentplatform.domain.workspace.WorkspaceFailure;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController @Profile("workspace-runner")
@ConditionalOnProperty(name={"platform.workspace.files.enabled","platform.workspace.tools.enabled"},havingValue="true")
@RequestMapping("/internal/file-captures/v1")
public final class FileCaptureRunnerController {
    private final FileCaptureRunnerApi service;
    public FileCaptureRunnerController(FileCaptureRunnerApi service){this.service=service;}
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<byte[]> operate(@AuthenticationPrincipal Jwt jwt,@RequestBody byte[] body,HttpServletRequest request) {
        route(request);return result(service.operate(jwt.getTokenValue(),body));
    }
    @GetMapping("/{id}") ResponseEntity<byte[]> query(@AuthenticationPrincipal Jwt jwt,@PathVariable("id")String id,HttpServletRequest request) {
        route(request);return result(service.query(jwt.getTokenValue(),id));
    }
    private static void route(HttpServletRequest request){if(request.getQueryString()!=null)throw new WorkspaceFailure(400,"INVALID_RUNNER_ROUTE");}
    private static ResponseEntity<byte[]> result(SignedResult result){return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header("Cache-Control","no-store")
        .header("X-Runner-Signature",result.signature()).body(result.body());}
}
