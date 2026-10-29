package com.agentplatform.trigger.http;

import com.agentplatform.api.files.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("workspace-api")
@ConditionalOnProperty(name={"platform.workspace.enabled","platform.workspace.files.enabled"},havingValue="true")
@RequestMapping("/api/v1")
public final class FileMutationController {
    private final FileMutationApi service;
    public FileMutationController(FileMutationApi service){this.service=service;}
    @GetMapping("/files/{id}/mutation-preview")
    ResponseEntity<FileMutationApi.Preview> preview(HttpServletRequest request,@PathVariable("id")String id) {
        var actor=RequestIdentity.caller(request);
        return ResponseEntity.ok().header("Cache-Control","no-store").body(service.preview(new FileApi.Caller(actor.userId(),actor.spaceId()),id));
    }
    @GetMapping("/workspaces/{id}/deleted-files")
    ResponseEntity<FileMutationApi.DeletedPage> deleted(HttpServletRequest request,@PathVariable("id")String id,
            @RequestParam(name="cursor",required=false)String cursor,@RequestParam(name="limit",defaultValue="100")int limit) {
        var actor=RequestIdentity.caller(request);
        return ResponseEntity.ok().header("Cache-Control","no-store").body(service.deleted(new FileApi.Caller(actor.userId(),actor.spaceId()),id,cursor,limit));
    }
    @GetMapping("/files/{id}/mutation-requests/{requestId}")
    ResponseEntity<FileMutationApi.RequestView> request(HttpServletRequest request,@PathVariable("id")String id,@PathVariable("requestId")String requestId) {
        var actor=RequestIdentity.caller(request);
        return ResponseEntity.ok().header("Cache-Control","no-store").body(service.request(new FileApi.Caller(actor.userId(),actor.spaceId()),id,requestId));
    }
    @PostMapping("/files/{id}/restore")
    ResponseEntity<FileApi.OperationView> restore(HttpServletRequest request,@PathVariable("id")String id,@RequestBody FileMutationApi.Restore body) {
        var actor=RequestIdentity.caller(request);
        return ResponseEntity.accepted().header("Cache-Control","no-store").body(service.restore(new FileApi.Caller(actor.userId(),actor.spaceId()),id,
                request.getHeader("Idempotency-Key"),request.getHeader("If-Match"),body));
    }
    @DeleteMapping("/files/{id}")
    ResponseEntity<FileApi.OperationView> delete(HttpServletRequest request,@PathVariable("id")String id) {
        var actor=RequestIdentity.caller(request);
        return ResponseEntity.accepted().header("Cache-Control","no-store").body(service.delete(
                new FileApi.Caller(actor.userId(),actor.spaceId()),id,request.getHeader("Idempotency-Key"),request.getHeader("If-Match")));
    }
    @PostMapping("/files/{id}/move")
    ResponseEntity<FileApi.OperationView> move(HttpServletRequest request,@PathVariable("id")String id,@RequestBody FileMutationApi.Move body) {
        var actor=RequestIdentity.caller(request);
        return ResponseEntity.accepted().header("Cache-Control","no-store").body(service.move(
                new FileApi.Caller(actor.userId(),actor.spaceId()),id,request.getHeader("Idempotency-Key"),request.getHeader("If-Match"),body));
    }
}
