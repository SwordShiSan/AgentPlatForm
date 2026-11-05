package com.agentplatform.trigger.http;

import com.agentplatform.api.files.AttachmentApi;
import com.agentplatform.api.identity.IdentityApi;
import com.agentplatform.domain.files.FileFailure;
import com.agentplatform.domain.identity.IdentityFailure;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @Profile("workspace-api")
@ConditionalOnProperty(name="platform.attachments.enabled",havingValue="true")
@RequestMapping("/api/v1")
public final class AttachmentController {
    public record Body(@NotBlank String draftId,@NotBlank @Size(max=1024) String filename,
            @NotNull @Min(0) @Max(104857600) Long bytes,@NotNull @Pattern(regexp="[a-f0-9]{64}") String sha256) {}
    private final AttachmentApi service;private final IdentityApi identity;
    public AttachmentController(AttachmentApi service,IdentityApi identity){this.service=service;this.identity=identity;}
    private static AttachmentApi.Caller caller(HttpServletRequest request){var c=RequestIdentity.caller(request);return new AttachmentApi.Caller(c.userId(),c.spaceId());}
    private static String requestId(HttpServletRequest request){var id=request.getHeader("Idempotency-Key");if(id==null)throw new FileFailure(428,"IDEMPOTENCY_KEY_REQUIRED");return id;}
    private void session(HttpServletRequest request,AttachmentApi.Caller caller){
        var current=identity.me(RequestIdentity.token(request),request.getHeader("X-Space-Id"));
        if(current==null||!caller.userId().equals(current.id())||current.spaces().stream().noneMatch(s->caller.spaceId().equals(s.id())))throw new IdentityFailure(401,"AUTHENTICATION_REQUIRED");
    }
    @PostMapping("/attachment-uploads") ResponseEntity<AttachmentApi.Upload> create(HttpServletRequest request,@Valid @RequestBody Body body){
        return ResponseEntity.status(201).header("Cache-Control","no-store").body(service.create(caller(request),requestId(request),new AttachmentApi.Create(body.draftId(),body.filename(),body.bytes(),body.sha256())));
    }
    @GetMapping("/attachment-uploads/{id}") AttachmentApi.Upload upload(HttpServletRequest request,@PathVariable("id")String id){return service.upload(caller(request),id);}
    @GetMapping("/attachment-uploads/requests/{id}") AttachmentApi.Upload original(HttpServletRequest request,@PathVariable("id")String id){return service.request(caller(request),id);}
    @PutMapping(value="/attachment-uploads/{id}/content",consumes="application/octet-stream") AttachmentApi.Upload transfer(HttpServletRequest request,@PathVariable("id")String id)throws java.io.IOException{
        var caller=caller(request);try(var source=new SessionCheckedInputStream(request.getInputStream(),()->session(request,caller))){return service.transfer(caller,id,request.getContentLengthLong(),source);}
    }
    @PostMapping("/attachment-uploads/{id}/complete") ResponseEntity<AttachmentApi.View> complete(HttpServletRequest request,@PathVariable("id")String id){
        if(request.getContentLengthLong()>0||request.getHeader("Transfer-Encoding")!=null)throw new FileFailure(400,"EMPTY_FILE_COMMAND_REQUIRED");
        return ResponseEntity.accepted().header("Cache-Control","no-store").body(service.complete(caller(request),id));
    }
    @GetMapping("/attachments/{id}") ResponseEntity<AttachmentApi.View> get(HttpServletRequest request,@PathVariable("id")String id){var view=service.get(caller(request),id);return ResponseEntity.ok().header("Cache-Control","no-store").eTag("\""+view.revision()+"\"").body(view);}
    @GetMapping("/me/storage-usage") ResponseEntity<AttachmentApi.Usage> usage(HttpServletRequest request){return ResponseEntity.ok().header("Cache-Control","no-store").body(service.usage(caller(request)));}
    @GetMapping("/attachments/{id}/content") void content(HttpServletRequest request,HttpServletResponse response,@PathVariable("id")String id)throws java.io.IOException{
        var caller=caller(request);session(request,caller);
        try(var content=service.content(caller,id)){
            response.setContentType("application/octet-stream");response.setContentLengthLong(content.bytes());response.setHeader("Cache-Control","no-store");response.setHeader("X-Content-Type-Options","nosniff");
            response.setHeader("Content-Disposition",ContentDisposition.attachment().filename(content.filename(),java.nio.charset.StandardCharsets.UTF_8).build().toString());
            response.setHeader("ETag","\""+content.sha256()+"\"");var checked=new SessionCheckedInputStream(content.stream(),()->session(request,caller));
            byte[] chunk=new byte[65536];int read;var out=response.getOutputStream();while((read=checked.read(chunk))!=-1)out.write(chunk,0,read);out.flush();
        }catch(RuntimeException|java.io.IOException failure){if(!response.isCommitted()){response.reset();throw failure;}response.getOutputStream().close();}
    }
}
