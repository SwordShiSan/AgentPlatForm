package com.agentplatform.trigger.http;

import com.agentplatform.api.files.FileApi;
import com.agentplatform.api.identity.IdentityApi;
import com.agentplatform.domain.identity.IdentityFailure;
import com.agentplatform.domain.files.FileFailure;
import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("workspace-api")
@ConditionalOnProperty(name={"platform.workspace.enabled","platform.workspace.files.enabled"},havingValue="true")
@RequestMapping("/api/v1")
public final class FileController {
    public record UploadBody(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=4096) String path,
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(104857600) Long bytes,
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Pattern(regexp="[a-f0-9]{64}") String sha256,
            String fileId,String baseVersionId) {}
    private final FileApi service;
    private final IdentityApi identity;
    public FileController(FileApi service,IdentityApi identity) { this.service=service;this.identity=identity; }
    private void session(HttpServletRequest request,FileApi.Caller caller) {
        var current=identity.me(RequestIdentity.token(request),request.getHeader("X-Space-Id"));
        if(current==null||!caller.userId().equals(current.id())||current.spaces().stream().noneMatch(s->caller.spaceId().equals(s.id())))
            throw new IdentityFailure(401,"AUTHENTICATION_REQUIRED");
    }
    private static FileApi.Caller caller(HttpServletRequest request) {
        var identity=RequestIdentity.caller(request);return new FileApi.Caller(identity.userId(),identity.spaceId());
    }
    @GetMapping("/files/{id}/versions")
    ResponseEntity<FileApi.VersionPage> versions(HttpServletRequest request,@PathVariable("id") String id,
            @RequestParam(name="cursor",required=false) String cursor,@RequestParam(name="limit",defaultValue="100") int limit) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(service.versions(caller(request),id,cursor,limit));
    }
    @GetMapping("/tasks/{id}/artifacts")
    ResponseEntity<FileApi.ArtifactPage> artifacts(HttpServletRequest request,@PathVariable("id") String id,
            @RequestParam(name="cursor",required=false) String cursor,@RequestParam(name="limit",defaultValue="100") int limit) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(service.artifacts(caller(request),id,cursor,limit));
    }
    @GetMapping("/artifacts/{id}")
    ResponseEntity<FileApi.ArtifactView> artifact(HttpServletRequest request,@PathVariable("id") String id) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(service.artifact(caller(request),id));
    }
    @GetMapping("/artifacts/{id}/download")
    void artifactDownload(HttpServletRequest request,HttpServletResponse response,@PathVariable("id") String id) throws java.io.IOException {
        var actor=caller(request);
        send(request,response,actor,()->service.artifactDownload(actor,id,request.getHeader("Range")));
    }
    @PostMapping("/workspaces/{id}/uploads")
    ResponseEntity<FileApi.UploadView> create(HttpServletRequest request,@PathVariable String id,@jakarta.validation.Valid @RequestBody UploadBody body) {
        String key=request.getHeader("Idempotency-Key");if(key==null)throw new FileFailure(428,"IDEMPOTENCY_KEY_REQUIRED");
        var result=service.create(caller(request),id,key,request.getHeader("If-Match"),new FileApi.CreateUpload(body.path(),body.bytes(),body.sha256(),body.fileId(),body.baseVersionId()));
        return ResponseEntity.status(201).header("Cache-Control","no-store").body(result);
    }
    @GetMapping("/uploads/{id}") ResponseEntity<FileApi.UploadView> upload(HttpServletRequest request,@PathVariable String id) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(service.upload(caller(request),id));
    }
    @PutMapping(value="/uploads/{id}/content",consumes="application/octet-stream")
    ResponseEntity<FileApi.UploadView> content(HttpServletRequest request,@PathVariable String id) throws java.io.IOException {
        var caller=caller(request);
        try(var stream=new SessionCheckedInputStream(request.getInputStream(),()->session(request,caller))) {
            return ResponseEntity.ok().header("Cache-Control","no-store").body(service.transfer(caller,id,request.getContentLengthLong(),stream));
        }
    }
    @PostMapping("/uploads/{id}/complete") ResponseEntity<FileApi.OperationView> complete(HttpServletRequest request,@PathVariable String id) {
        if(request.getContentLengthLong()>0||request.getHeader("Transfer-Encoding")!=null)throw new FileFailure(400,"EMPTY_FILE_COMMAND_REQUIRED");
        return ResponseEntity.accepted().header("Cache-Control","no-store").body(service.complete(caller(request),id));
    }
    @GetMapping("/file-operations/{id}") ResponseEntity<FileApi.OperationView> operation(HttpServletRequest request,@PathVariable String id) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(service.operation(caller(request),id));
    }
    @GetMapping("/workspaces/{id}/files") FileApi.Page list(HttpServletRequest request,HttpServletResponse response,@PathVariable String id,
            @RequestParam(required=false) String parent,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="100") int limit) {
        response.setHeader("Cache-Control","no-store");return service.list(caller(request),id,parent,cursor,limit);
    }
    @GetMapping("/files/{id}") ResponseEntity<FileApi.FileView> file(HttpServletRequest request,@PathVariable String id) {
        var result=service.file(caller(request),id);
        return ResponseEntity.ok().header("Cache-Control","no-store").eTag("\""+result.revision()+"\"").body(result);
    }
    @GetMapping("/workspaces/{id}/storage-usage") FileApi.UsageView usage(HttpServletRequest request,HttpServletResponse response,@PathVariable String id) {
        response.setHeader("Cache-Control","no-store");return service.usage(caller(request),id);
    }
    @GetMapping("/files/{id}/content") void download(HttpServletRequest request,HttpServletResponse response,@PathVariable String id,
            @RequestParam(required=false) String versionId) throws java.io.IOException {
        var caller=caller(request);
        send(request,response,caller,()->service.download(caller,id,versionId,request.getHeader("Range")));
    }
    private void send(HttpServletRequest request,HttpServletResponse response,FileApi.Caller caller,
            java.util.function.Supplier<FileApi.Download> source) throws java.io.IOException {
        session(request,caller);
        try(var content=source.get()) {
            var checked=new SessionCheckedInputStream(content.stream(),()->session(request,caller));
            response.setStatus(content.first()==null?200:206);
            response.setHeader("Cache-Control","no-store");response.setHeader("X-Content-Type-Options","nosniff");
            response.setHeader("Accept-Ranges","bytes");response.setHeader("ETag","\""+content.sha256()+"\"");
            response.setContentType("application/octet-stream");response.setContentLengthLong(content.bytes());
            response.setHeader("Content-Disposition",ContentDisposition.attachment().filename(content.filename(),StandardCharsets.UTF_8).build().toString());
            if(content.first()!=null)response.setHeader("Content-Range","bytes "+content.first()+"-"+content.last()+"/"+content.totalBytes());
            byte[] buffer=new byte[65536];int read;var target=response.getOutputStream();
            while((read=checked.read(buffer))!=-1)target.write(buffer,0,read);
            target.flush();
        } catch(RuntimeException|java.io.IOException failure) {
            if(!response.isCommitted()){response.reset();throw failure;}
            // A partial binary response cannot be replaced with a Problem JSON body.
            response.getOutputStream().close();
        }
    }
}
