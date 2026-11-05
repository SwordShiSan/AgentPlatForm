package com.agentplatform.app.attachment;

import com.agentplatform.api.files.AttachmentApi;
import com.agentplatform.app.files.AuthorizedAssetStream;
import com.agentplatform.domain.attachment.*;
import com.agentplatform.domain.attachment.AttachmentData;
import com.agentplatform.domain.files.*;
import com.agentplatform.domain.files.FileData.Actor;
import com.agentplatform.infrastructure.workspace.RunnerSignatures;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Original bytes stream once into private storage, independently of any workspace. */
public final class AttachmentService implements AttachmentApi {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final AttachmentRepository repository;private final AssetStore assets;
    public AttachmentService(AttachmentRepository repository,AssetStore assets){this.repository=repository;this.assets=assets;}
    private static Actor actor(Caller caller){return new Actor(FileRules.id(caller.userId()),FileRules.id(caller.spaceId()));}
    private static Upload view(AttachmentData.Upload row){return new Upload(row.id().toString(),row.attachmentId().toString(),row.status(),row.sizeBytes(),row.sha256(),row.revision(),row.expiresAt().toString(),
            "CREATED".equals(row.status())?List.of("UPLOAD","QUERY"):"VERIFIED".equals(row.status())?List.of("COMPLETE","QUERY"):List.of("QUERY"));}
    private static View view(AttachmentData.Attachment row){return new View(row.id().toString(),row.filename(),row.sizeBytes(),row.sha256(),row.status(),row.conversationId()==null?null:row.conversationId().toString(),row.revision(),
            "AVAILABLE".equals(row.status())?List.of("DOWNLOAD","EXTRACT","PREVIEW"):List.of("QUERY"));}
    @Override public Upload create(Caller caller,String requestId,Create body){
        if(body==null)throw new FileFailure(400,"INVALID_ATTACHMENT_REQUEST");
        String hash=RunnerSignatures.sha256(JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
        return view(repository.create(actor(caller),FileRules.id(requestId),hash,new AttachmentData.Create(FileRules.id(body.draftId()),body.filename(),body.bytes(),body.sha256())));
    }
    @Override public Upload upload(Caller caller,String id){
        var actor=actor(caller);var row=repository.upload(actor,FileRules.id(id));
        if(Set.of("UNKNOWN","UPLOADING").contains(row.status()))try{
            // SerialAssetStore inspection can acquire its persistent slot only after the
            // preceding physical transport has a real release fact. No timeout inference.
            var object=assets.inspect(row.objectKey());
            if(object.bytes()==row.sizeBytes()&&object.sha256().equals(row.sha256()))row=repository.recoverVerified(actor,row.id(),row.revision());
        }catch(FileFailure pending){if(!Set.of(404,409,423,503).contains(pending.status()))throw pending;}
        return view(row);
    }
    @Override public Upload request(Caller caller,String id){return view(repository.request(actor(caller),FileRules.id(id)));}
    @Override public Upload transfer(Caller caller,String id,long length,InputStream source){
        if(length<0)throw new FileFailure(411,"CONTENT_LENGTH_REQUIRED");
        var a=actor(caller);UUID uploadId=FileRules.id(id);var row=repository.upload(a,uploadId);
        if(length!=row.sizeBytes())throw new FileFailure(400,"FILE_CONTENT_LENGTH_MISMATCH");
        UUID owner=UUID.randomUUID();row=repository.begin(a,uploadId,owner);
        try{assets.put(row.objectKey(),length,row.sha256(),new AuthorizedAssetStream(source,length,row.sha256(),()->repository.guard(a,uploadId,owner)));repository.verified(a,uploadId,owner);}
        catch(RuntimeException failure){try{if(failure instanceof FileFailure rejected&&rejected.status()==423)repository.rejected(a,uploadId,owner);else repository.uncertain(a,uploadId,owner);}catch(RuntimeException unresolved){failure.addSuppressed(unresolved);}throw failure;}
        return view(repository.upload(a,uploadId));
    }
    @Override public View complete(Caller caller,String id){return view(repository.complete(actor(caller),FileRules.id(id)));}
    @Override public View get(Caller caller,String id){return view(repository.get(actor(caller),FileRules.id(id)));}
    @Override public Usage usage(Caller caller){var usage=repository.usage(actor(caller));return new Usage(usage.objectLimitBytes(),usage.usedBytes(),usage.reservedBytes(),usage.retainedBytes(),usage.categories().stream().map(c->new Category(c.category(),c.bytes(),c.objects())).toList());}
    @Override public Content content(Caller caller,String id){
        var a=actor(caller);UUID attachment=FileRules.id(id);var row=repository.get(a,attachment);
        if(!"AVAILABLE".equals(row.status()))throw new FileFailure(409,"ATTACHMENT_NOT_AVAILABLE");
        var content=assets.open(row.objectKey(),null,null);
        if(content.bytes()!=row.sizeBytes()||content.totalBytes()!=row.sizeBytes()){
            try{content.close();}catch(java.io.IOException ignored){}throw new FileFailure(503,"ASSET_IDENTITY_MISMATCH");
        }
        return new Content(new AuthorizedAssetStream(content.stream(),row.sizeBytes(),row.sha256(),()->repository.get(a,attachment)),row.sizeBytes(),row.sha256(),row.filename());
    }
}
