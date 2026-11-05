package com.agentplatform.trigger.http;

import com.agentplatform.api.files.DocumentExtractionApi;
import com.agentplatform.domain.files.FileFailure;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;

@RestController @Profile("workspace-api") @RequestMapping("/api/v1")
@ConditionalOnProperty(name="platform.attachments.enabled",havingValue="true")
public final class DocumentExtractionController {
    private final DocumentExtractionApi service;
    public DocumentExtractionController(DocumentExtractionApi service){this.service=service;}
    private static DocumentExtractionApi.Caller caller(HttpServletRequest request){var c=RequestIdentity.caller(request);return new DocumentExtractionApi.Caller(c.userId(),c.spaceId());}
    @PostMapping("/file-extractions") ResponseEntity<DocumentExtractionApi.View> create(HttpServletRequest request,@RequestBody DocumentExtractionApi.Create body){
        var key=request.getHeader("Idempotency-Key");if(key==null)throw new FileFailure(428,"IDEMPOTENCY_KEY_REQUIRED");
        var view=service.create(caller(request),key,body);return ResponseEntity.accepted().header("Cache-Control","no-store").eTag("\""+view.revision()+"\"").body(view);
    }
    @GetMapping("/file-extractions/{id}") ResponseEntity<DocumentExtractionApi.View> get(HttpServletRequest request,@PathVariable("id")String id){var view=service.get(caller(request),id);return ResponseEntity.ok().header("Cache-Control","no-store").eTag("\""+view.revision()+"\"").body(view);}
}
