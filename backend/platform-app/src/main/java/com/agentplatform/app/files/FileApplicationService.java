package com.agentplatform.app.files;

import com.agentplatform.api.files.FileApi;
import com.agentplatform.domain.files.*;
import com.agentplatform.domain.files.FileData.*;
import com.agentplatform.infrastructure.workspace.RunnerSignatures;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import tools.jackson.databind.json.JsonMapper;

/** Metadata transactions are short; streaming occurs outside transactions. */
public final class FileApplicationService implements FileApi {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final FileRepository repository;
    private final AssetStore assets;
    private final Semaphore downloadSlot=new Semaphore(1);
    public FileApplicationService(FileRepository repository, AssetStore assets) { this.repository=repository;this.assets=assets; }
    private static Actor actor(Caller caller) { return new Actor(FileRules.id(caller.userId()),FileRules.id(caller.spaceId())); }
    private static String id(UUID id) { return id==null?null:id.toString(); }
    private static UploadView view(Upload row) {
        List<String> actions=switch(row.status()) {case "CREATED"->List.of("UPLOAD","READ");case "VERIFIED"->List.of("COMPLETE","READ");default->List.of("READ");};
        return new UploadView(id(row.id()),id(row.workspaceId()),id(row.fileId()),id(row.versionId()),row.relativePath(),row.sizeBytes(),
                row.sha256(),row.status(),id(row.operationId()),row.revision(),row.expiresAt().toString(),actions);
    }
    private static FileView view(Entry row) {
        return new FileView(id(row.id()),id(row.workspaceId()),row.relativePath(),row.parentPath(),row.name(),row.kind(),id(row.currentVersionId()),row.collectionState(),row.revision(),row.sha256(),row.sizeBytes());
    }
    private static OperationView view(Operation row) {
        return new OperationView(id(row.id()),id(row.workspaceId()),id(row.uploadId()),row.status(),row.revision(),row.errorCode(),List.of("READ"));
    }
    @Override public UploadView create(Caller caller,String workspace,String requestId,String ifMatch,CreateUpload body) {
        if(body==null)throw new FileFailure(400,"INVALID_UPLOAD_REQUEST");
        Long expected=null;
        if(body.fileId()!=null) {
            if(ifMatch==null)throw new FileFailure(428,"VERSION_REQUIRED");
            try {
                if(!ifMatch.matches("\"[1-9][0-9]*\""))throw new IllegalArgumentException();
                expected=Long.parseLong(ifMatch.substring(1,ifMatch.length()-1));
            } catch(IllegalArgumentException invalid) { throw new FileFailure(400,"INVALID_VERSION"); }
        } else if(ifMatch!=null)throw new FileFailure(400,"INVALID_UPLOAD_MODE");
        var request=new UploadRequest(body.path(),body.bytes(),body.sha256(),body.fileId()==null?null:FileRules.id(body.fileId()),
                body.baseVersionId()==null?null:FileRules.id(body.baseVersionId()),expected);
        String digest=RunnerSignatures.sha256((workspace+"\n"+JSON.writeValueAsString(request)).getBytes(StandardCharsets.UTF_8));
        return view(repository.create(actor(caller),FileRules.id(workspace),FileRules.id(requestId),digest,request));
    }
    @Override public UploadView upload(Caller caller,String id) { return view(repository.upload(actor(caller),FileRules.id(id))); }
    @Override public UploadView transfer(Caller caller,String id,long contentLength,InputStream content) {
        if(contentLength<0)throw new FileFailure(411,"CONTENT_LENGTH_REQUIRED");
        var actor=actor(caller);var upload=repository.upload(actor,FileRules.id(id));
        if(contentLength!=upload.sizeBytes())throw new FileFailure(400,"FILE_CONTENT_LENGTH_MISMATCH");
        if("VERIFIED".equals(upload.status())||"QUEUED".equals(upload.status())||"COMMITTED".equals(upload.status()))
            throw new FileFailure(409,"UPLOAD_CONTENT_ALREADY_CONFIRMED");
        UUID owner=UUID.randomUUID();upload=repository.beginTransfer(actor,upload.id(),owner);
        final UUID uploadId=upload.id();
        try {
            assets.put(upload.objectKey(),upload.sizeBytes(),upload.sha256(),
                    new AuthorizedAssetStream(content,upload.sizeBytes(),upload.sha256(),()->repository.guardTransfer(actor,uploadId,owner)));
            repository.transferVerified(actor,upload.id(),owner);
        } catch(RuntimeException failure) {
            try {
                if(failure instanceof FileFailure rejected&&rejected.status()==423)repository.transferRejected(actor,upload.id(),owner);
                else repository.transferUncertain(actor,upload.id(),owner);
            } catch(RuntimeException pending) { failure.addSuppressed(pending); }
            throw failure;
        }
        return view(repository.upload(actor,upload.id()));
    }
    @Override public OperationView complete(Caller caller,String id) { return view(repository.completeUpload(actor(caller),FileRules.id(id))); }
    @Override public OperationView operation(Caller caller,String id) { return view(repository.operation(actor(caller),FileRules.id(id))); }
    @Override public FileView file(Caller caller,String id) { return view(repository.file(actor(caller),FileRules.id(id))); }
    private static ArtifactView artifactView(Artifact a) {
        return new ArtifactView(id(a.id()),id(a.workspaceId()),id(a.taskId()),id(a.operationId()),id(a.fileId()),id(a.versionId()),
                a.title(),a.sha256(),a.sizeBytes(),"VERIFIED_CONTENT",a.evidenceStatus());
    }
    @Override public ArtifactPage artifacts(Caller caller,String task,String cursor,int limit) {
        if(limit<1||limit>100)throw new FileFailure(400,"INVALID_FILE_PAGE");
        var rows=repository.artifacts(actor(caller),FileRules.id(task),cursor==null?null:FileRules.id(cursor),limit+1);
        boolean more=rows.size()>limit;var selected=more?rows.subList(0,limit):rows;
        return new ArtifactPage(selected.stream().map(FileApplicationService::artifactView).toList(),more?id(selected.getLast().id()):null);
    }
    @Override public ArtifactView artifact(Caller caller,String id) {return artifactView(repository.artifact(actor(caller),FileRules.id(id)));}
    @Override public Download artifactDownload(Caller caller,String id,String range) {
        var row=repository.artifact(actor(caller),FileRules.id(id));
        var content=download(caller,row.fileId().toString(),row.versionId().toString(),range);
        String filename=row.title().substring(row.title().lastIndexOf('/')+1);
        return new Download(content.stream(),content.bytes(),content.totalBytes(),content.first(),content.last(),content.sha256(),filename,content.mime());
    }
    @Override public VersionPage versions(Caller caller,String file,String cursor,int limit) {
        if(limit<1||limit>100)throw new FileFailure(400,"INVALID_FILE_PAGE");
        var rows=repository.versions(actor(caller),FileRules.id(file),cursor==null?null:FileRules.id(cursor),limit+1);
        boolean more=rows.size()>limit;
        var selected=more?rows.subList(0,limit):rows;
        return new VersionPage(selected.stream().map(v->new VersionView(id(v.id()),id(v.fileId()),v.sha256(),
                v.sizeBytes(),v.createdAt().toString(),id(v.sourceVersionId()))).toList(),
                more?id(selected.getLast().id()):null);
    }
    @Override public Page list(Caller caller,String workspace,String parent,String cursor,int limit) {
        if(limit<1||limit>100)throw new FileFailure(400,"INVALID_FILE_PAGE");
        String after=null;
        if(cursor!=null)try {
            after=FileRules.path(new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8),false);
        } catch(IllegalArgumentException invalid) { throw new FileFailure(400,"INVALID_FILE_CURSOR"); }
        var rows=repository.list(actor(caller),FileRules.id(workspace),parent==null?"":parent,after,limit);
        String next=rows.size()==limit?Base64.getUrlEncoder().withoutPadding().encodeToString(rows.getLast().relativePath().getBytes(StandardCharsets.UTF_8)):null;
        return new Page(rows.stream().map(FileApplicationService::view).toList(),next);
    }
    @Override public Download download(Caller caller,String id,String version,String rangeHeader) {
        var actor=actor(caller);var file=repository.file(actor,FileRules.id(id));
        if(!"FILE".equals(file.kind()))throw new FileFailure(400,"FILE_CONTENT_UNAVAILABLE");
        if("DELETED".equals(file.collectionState())&&version==null)throw new FileFailure(410,"FILE_DELETED");
        var row=repository.version(actor,file.id(),version==null?null:FileRules.id(version));
        var range=FileRanges.parse(rangeHeader,row.sizeBytes());
        if(!downloadSlot.tryAcquire())throw new FileFailure(423,"FILE_TRANSFER_BUSY");
        try {
            var object=assets.open(row.objectKey(),range==null?null:range.first(),range==null?null:range.last());
            long expected=range==null?row.sizeBytes():range.length();
            if(object.bytes()!=expected||object.totalBytes()!=row.sizeBytes()) {
                object.close();throw new FileFailure(503,"ASSET_IDENTITY_MISMATCH");
            }
            var checked=new AuthorizedAssetStream(object.stream(),expected,range==null?row.sha256():null,()->repository.version(actor,file.id(),row.id()));
            var stream=new java.io.FilterInputStream(checked) {
                boolean closed;
                @Override public void close() throws java.io.IOException {
                    if(!closed){closed=true;try{super.close();}finally{downloadSlot.release();}}
                }
            };
            return new Download(stream,expected,row.sizeBytes(),range==null?null:range.first(),range==null?null:range.last(),row.sha256(),file.name(),row.mime());
        } catch(java.io.IOException failure) {
            downloadSlot.release();throw new FileFailure(503,"FILE_DOWNLOAD_UNAVAILABLE");
        } catch(RuntimeException failure) {downloadSlot.release();throw failure;}
    }
    @Override public UsageView usage(Caller caller,String workspace) {
        var row=repository.usage(actor(caller),FileRules.id(workspace));
        return new UsageView(row.objectLimitBytes(),row.reservedBytes(),row.committedBytes(),row.workspaceQuotaBytes(),row.workspaceQuotaInodes(),row.measuredBytes(),row.measuredInodes(),row.measuredAt()==null?null:row.measuredAt().toString());
    }
}
