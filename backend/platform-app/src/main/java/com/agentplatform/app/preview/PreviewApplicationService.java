package com.agentplatform.app.preview;

import com.agentplatform.api.files.PreviewApi;
import com.agentplatform.app.files.FileRanges;
import com.agentplatform.app.files.AuthorizedAssetStream;
import com.agentplatform.domain.files.*;
import com.agentplatform.domain.files.FileData.Actor;
import com.agentplatform.domain.preview.*;
import com.agentplatform.domain.preview.PreviewData.Job;
import com.agentplatform.infrastructure.workspace.RunnerSignatures;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class PreviewApplicationService implements PreviewApi {
    private final PreviewRepository repository;private final AssetStore assets;
    public PreviewApplicationService(PreviewRepository repository,AssetStore assets){this.repository=repository;this.assets=assets;}
    private static Actor actor(Caller c){return new Actor(FileRules.id(c.userId()),FileRules.id(c.spaceId()));}
    private static View view(Job r){return new View(r.id().toString(),r.fileId().toString(),r.versionId().toString(),r.state(),r.format()==null?"AUTO":r.format(),r.mediaType(),r.bytes(),r.sha256(),r.pages(),r.errorCode(),r.retryable(),r.revision(),PreviewData.actions(r));}
    @Override public View create(Caller caller,String file,Create body,String request) {
        if(body==null||body.versionId()==null)throw new FileFailure(400,"INVALID_PREVIEW_REQUEST");
        UUID f=FileRules.id(file),v=FileRules.id(body.versionId());String format=body.format()==null?"AUTO":body.format();
        var hash=RunnerSignatures.sha256((f+"\n"+v+"\n"+format).getBytes(StandardCharsets.UTF_8));
        return view(repository.create(actor(caller),f,v,format,FileRules.id(request),hash));
    }
    @Override public View get(Caller caller,String preview){return view(repository.get(actor(caller),FileRules.id(preview)));}
    @Override public View retry(Caller caller,String preview,String ifMatch,String request) {
        if(ifMatch==null)throw new FileFailure(428,"VERSION_REQUIRED");
        long version;try{if(!ifMatch.matches("\"[1-9][0-9]*\""))throw new IllegalArgumentException();version=Long.parseLong(ifMatch.substring(1,ifMatch.length()-1));}catch(IllegalArgumentException invalid){throw new FileFailure(400,"INVALID_VERSION");}
        UUID id=FileRules.id(preview);String hash=RunnerSignatures.sha256((id+"\n"+version).getBytes(StandardCharsets.UTF_8));
        return view(repository.retry(actor(caller),id,version,FileRules.id(request),hash));
    }
    @Override public Content content(Caller caller,String preview,String rangeHeader) {
        var a=actor(caller);UUID id=FileRules.id(preview);var row=repository.get(a,id);
        if(!"READY".equals(row.state()))throw new FileFailure(409,"PREVIEW_NOT_READY");
        if(rangeHeader!=null&&!"PDF".equals(row.format()))throw new FileFailure(416,"INVALID_FILE_RANGE");
        var range=FileRanges.parse(rangeHeader,row.bytes());var content=assets.open(row.outputObjectKey(),range==null?null:range.first(),range==null?null:range.last());
        long bytes=range==null?row.bytes():range.length();
        if(content.bytes()!=bytes||content.totalBytes()!=row.bytes()){try{content.close();}catch(java.io.IOException ignored){}throw new FileFailure(503,"ASSET_IDENTITY_MISMATCH");}
        return new Content(new AuthorizedAssetStream(content.stream(),bytes,range==null?row.sha256():null,()->repository.get(a,id)),bytes,row.bytes(),range==null?null:range.first(),range==null?null:range.last(),row.sha256(),row.mediaType());
    }
}
