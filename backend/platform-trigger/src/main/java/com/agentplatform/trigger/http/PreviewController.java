package com.agentplatform.trigger.http;

import com.agentplatform.api.files.PreviewApi;
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
@ConditionalOnProperty(name={"platform.workspace.enabled","platform.workspace.files.enabled","platform.preview.enabled"},havingValue="true")
@RequestMapping("/api/v1")
public final class PreviewController {
    public record Body(@NotBlank String versionId,@Pattern(regexp="AUTO|PDF|IMAGE|HTML_STATIC") String format) {}
    private final PreviewApi previews;private final IdentityApi identity;
    public PreviewController(PreviewApi previews,IdentityApi identity){this.previews=previews;this.identity=identity;}
    private static PreviewApi.Caller caller(HttpServletRequest request){var c=RequestIdentity.caller(request);return new PreviewApi.Caller(c.userId(),c.spaceId());}
    private static String requestId(HttpServletRequest request){var id=request.getHeader("Idempotency-Key");if(id==null)throw new FileFailure(428,"IDEMPOTENCY_KEY_REQUIRED");return id;}
    private static ResponseEntity<PreviewApi.View> view(PreviewApi.View view,boolean accepted){return ResponseEntity.status(accepted?202:200).header("Cache-Control","no-store").eTag("\""+view.revision()+"\"").body(view);}
    @PostMapping("/files/{id}/previews") ResponseEntity<PreviewApi.View> create(HttpServletRequest request,@PathVariable("id")String id,@Valid @RequestBody Body body){return view(previews.create(caller(request),id,new PreviewApi.Create(body.versionId(),body.format()),requestId(request)),true);}
    @GetMapping("/previews/{id}") ResponseEntity<PreviewApi.View> get(HttpServletRequest request,@PathVariable("id")String id){return view(previews.get(caller(request),id),false);}
    @PostMapping("/previews/{id}/retry") ResponseEntity<PreviewApi.View> retry(HttpServletRequest request,@PathVariable("id")String id){if(request.getContentLengthLong()>0||request.getHeader("Transfer-Encoding")!=null)throw new FileFailure(400,"EMPTY_PREVIEW_COMMAND_REQUIRED");return view(previews.retry(caller(request),id,request.getHeader("If-Match"),requestId(request)),true);}
    private void session(HttpServletRequest request,PreviewApi.Caller caller){var current=identity.me(RequestIdentity.token(request),request.getHeader("X-Space-Id"));if(current==null||!caller.userId().equals(current.id())||current.spaces().stream().noneMatch(s->caller.spaceId().equals(s.id())))throw new IdentityFailure(401,"AUTHENTICATION_REQUIRED");}
    @GetMapping("/previews/{id}/content") void content(HttpServletRequest request,HttpServletResponse response,@PathVariable("id")String id)throws java.io.IOException {
        var caller=caller(request);session(request,caller);
        try(var content=previews.content(caller,id,request.getHeader("Range"))) {
            if(!java.util.Set.of("application/pdf","image/png","text/plain").contains(content.mediaType()))throw new FileFailure(503,"PREVIEW_MEDIA_TYPE_INVALID");
            response.setStatus(content.first()==null?200:206);response.setContentType(content.mediaType());response.setCharacterEncoding("UTF-8");response.setContentLengthLong(content.bytes());
            response.setHeader("Cache-Control","no-store");response.setHeader("X-Content-Type-Options","nosniff");response.setHeader("Content-Security-Policy","sandbox; default-src 'none'; frame-ancestors 'self'");
            response.setHeader("ETag","\""+content.sha256()+"\"");response.setHeader("Accept-Ranges","application/pdf".equals(content.mediaType())?"bytes":"none");
            if(content.first()!=null)response.setHeader("Content-Range","bytes "+content.first()+"-"+content.last()+"/"+content.totalBytes());
            var checked=new SessionCheckedInputStream(content.stream(),()->session(request,caller));var target=response.getOutputStream();byte[] buffer=new byte[65536];int read;
            while((read=checked.read(buffer))!=-1)target.write(buffer,0,read);target.flush();
        }catch(RuntimeException|java.io.IOException failure){if(!response.isCommitted()){response.reset();throw failure;}response.getOutputStream().close();}
    }
}
