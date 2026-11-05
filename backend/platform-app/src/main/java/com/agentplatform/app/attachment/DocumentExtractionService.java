package com.agentplatform.app.attachment;

import com.agentplatform.api.files.DocumentExtractionApi;
import com.agentplatform.domain.files.*;
import com.agentplatform.domain.files.FileData.Actor;
import com.agentplatform.domain.preview.*;
import com.agentplatform.domain.preview.PreviewData.Job;
import com.agentplatform.infrastructure.workspace.RunnerSignatures;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Extraction is a real asset-processing job; it is never a chat task or a workspace. */
public final class DocumentExtractionService implements DocumentExtractionApi {
    private final PreviewRepository repository;
    public DocumentExtractionService(PreviewRepository repository){this.repository=repository;}
    private static Actor actor(Caller c){return new Actor(FileRules.id(c.userId()),FileRules.id(c.spaceId()));}
    private static String id(UUID value){return value==null?null:value.toString();}
    private static View view(Job r){return new View(id(r.id()),r.sourceKind(),id(r.fileId()),id(r.versionId()),id(r.attachmentId()),r.name(),r.state(),r.bytes(),r.sha256(),r.errorCode(),r.retryable(),r.revision(),PreviewData.actions(r));}
    @Override public View create(Caller caller,String request,Create body){
        if(body==null||!(body.attachmentId()!=null&&body.fileId()==null&&body.versionId()==null||body.attachmentId()==null&&body.fileId()!=null&&body.versionId()!=null))throw new FileFailure(400,"INVALID_EXTRACTION_SOURCE");
        String hash=RunnerSignatures.sha256((body.fileId()+"\n"+body.versionId()+"\n"+body.attachmentId()+"\nTEXT_EXTRACT").getBytes(StandardCharsets.UTF_8));
        var actor=actor(caller);UUID key=FileRules.id(request);
        return view(body.attachmentId()!=null?repository.createAttachment(actor,FileRules.id(body.attachmentId()),"TEXT_EXTRACT",key,hash)
                :repository.create(actor,FileRules.id(body.fileId()),FileRules.id(body.versionId()),"TEXT_EXTRACT",key,hash));
    }
    @Override public View get(Caller caller,String id){return view(repository.get(actor(caller),FileRules.id(id)));}
}
