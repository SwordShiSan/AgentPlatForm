package com.agentplatform.trigger.http;

import com.agentplatform.api.knowledge.KnowledgeApi;
import com.agentplatform.domain.files.FileFailure;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @Profile("workspace-api") @RequestMapping("/api/v1")
@ConditionalOnProperty(name="platform.knowledge.enabled",havingValue="true")
public final class KnowledgeController {
    private final KnowledgeApi service;
    public KnowledgeController(KnowledgeApi service){this.service=service;}
    @PostMapping("/knowledge-bases") ResponseEntity<KnowledgeApi.BaseView> create(HttpServletRequest r,@RequestBody KnowledgeApi.CreateBase body){
        return ResponseEntity.status(201).header("Cache-Control","no-store").body(service.createBase(caller(r),request(r),body));
    }
    @GetMapping("/knowledge-bases") ResponseEntity<?> bases(HttpServletRequest r){return noStore(service.bases(caller(r)));}
    @GetMapping("/knowledge-bases/{id}/documents") ResponseEntity<?> documents(HttpServletRequest r,@PathVariable String id){return noStore(service.documents(caller(r),id));}
    @PostMapping("/knowledge-bases/{id}/documents") ResponseEntity<KnowledgeApi.DocumentView> add(HttpServletRequest r,@PathVariable String id,@RequestBody KnowledgeApi.AddDocument body){
        return ResponseEntity.accepted().header("Cache-Control","no-store").body(service.addDocument(caller(r),id,request(r),body));
    }
    @PostMapping("/knowledge-documents/{id}/rebuild") ResponseEntity<KnowledgeApi.DocumentView> rebuild(HttpServletRequest r,@PathVariable String id){
        return ResponseEntity.accepted().header("Cache-Control","no-store").body(service.rebuild(caller(r),id,request(r)));
    }
    @DeleteMapping("/knowledge-documents/{id}") ResponseEntity<Void> delete(HttpServletRequest r,@PathVariable String id){service.deleteDocument(caller(r),id,request(r));return ResponseEntity.accepted().build();}
    @PostMapping("/knowledge/search") ResponseEntity<KnowledgeApi.SearchView> search(HttpServletRequest r,@RequestBody KnowledgeApi.Search body){return ResponseEntity.ok().header("Cache-Control","no-store").body(service.search(caller(r),body));}
    private static <T> ResponseEntity<T> noStore(T body){return ResponseEntity.ok().header("Cache-Control","no-store").body(body);}
    private static KnowledgeApi.Caller caller(HttpServletRequest r){var c=RequestIdentity.caller(r);return new KnowledgeApi.Caller(c.userId(),c.spaceId());}
    private static String request(HttpServletRequest r){String key=r.getHeader("Idempotency-Key");if(key==null)throw new FileFailure(428,"IDEMPOTENCY_KEY_REQUIRED");return key;}
}
