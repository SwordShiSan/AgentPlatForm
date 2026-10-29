package com.agentplatform.app.files;

import com.agentplatform.api.files.*;
import com.agentplatform.domain.files.*;
import com.agentplatform.infrastructure.workspace.RunnerSignatures;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Short lifecycle transactions; no filesystem, Docker or network calls. */
public final class FileMutationService implements FileMutationApi {
    private final FileMutationRepository repository;
    private final TransactionTemplate transactions;
    private final FileRepository files;
    private final AssetStore assets;
    private final FileApi fileApi;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    public FileMutationService(FileMutationRepository repository,TransactionTemplate transactions) {this(repository,transactions,null,null,null);}
    public FileMutationService(FileMutationRepository repository,TransactionTemplate transactions,FileRepository files,AssetStore assets,FileApi fileApi) {
        this.repository=repository;this.transactions=transactions;this.files=files;this.assets=assets;this.fileApi=fileApi;
    }
    private static long version(String header) {
        if(header==null)throw new FileFailure(428,"VERSION_REQUIRED");
        try {
            if(!header.matches("\"[1-9][0-9]*\""))throw new IllegalArgumentException();
            return Long.parseLong(header.substring(1,header.length()-1));
        }catch(IllegalArgumentException invalid){throw new FileFailure(400,"INVALID_VERSION");}
    }
    @Override public FileApi.OperationView move(FileApi.Caller caller,String file,String request,String ifMatch,Move body) {
        if(body==null||body.name()==null||body.name().contains("/"))throw new FileFailure(400,"INVALID_FILE_NAME");
        FileRules.path(body.name(),false);
        if(request==null)throw new FileFailure(428,"IDEMPOTENCY_KEY_REQUIRED");
        var actor=new FileData.Actor(FileRules.id(caller.userId()),FileRules.id(caller.spaceId()));
        long revision=version(ifMatch);
        String hash=RunnerSignatures.sha256(("MOVE\n"+file+"\n"+revision+"\n"+JSON.writeValueAsString(body)).getBytes(StandardCharsets.UTF_8));
        var result=transactions.execute(s->repository.move(actor,FileRules.id(file),FileRules.id(request),hash,revision,
                body.targetParentId()==null?null:FileRules.id(body.targetParentId()),body.name()));
        return new FileApi.OperationView(result.id().toString(),result.workspaceId().toString(),null,result.status(),result.revision(),result.errorCode(),List.of("READ"));
    }
    @Override public FileApi.OperationView delete(FileApi.Caller caller,String file,String request,String ifMatch) {
        if(request==null)throw new FileFailure(428,"IDEMPOTENCY_KEY_REQUIRED");
        var actor=new FileData.Actor(FileRules.id(caller.userId()),FileRules.id(caller.spaceId()));
        long revision=version(ifMatch);
        String hash=RunnerSignatures.sha256(("DELETE\n"+file+"\n"+revision).getBytes(StandardCharsets.UTF_8));
        var result=transactions.execute(s->repository.delete(actor,FileRules.id(file),FileRules.id(request),hash,revision));
        return new FileApi.OperationView(result.id().toString(),result.workspaceId().toString(),null,result.status(),result.revision(),result.errorCode(),List.of("READ"));
    }
    @Override public RequestView request(FileApi.Caller caller,String file,String request) {
        var actor=new FileData.Actor(FileRules.id(caller.userId()),FileRules.id(caller.spaceId()));
        var value=transactions.execute(s->repository.request(actor,FileRules.id(file),FileRules.id(request)));
        var operation=value.operationId()==null?null:fileApi.operation(caller,value.operationId().toString());
        return new RequestView(value.operationId()==null?null:value.operationId().toString(),value.uploadId()==null?null:value.uploadId().toString(),value.state(),operation);
    }
    @Override public DeletedPage deleted(FileApi.Caller caller,String workspace,String cursor,int limit) {
        if(limit<1||limit>100)throw new FileFailure(400,"INVALID_FILE_PAGE");
        var actor=new FileData.Actor(FileRules.id(caller.userId()),FileRules.id(caller.spaceId()));
        var rows=transactions.execute(s->repository.deleted(actor,FileRules.id(workspace),cursor==null?null:FileRules.id(cursor),limit+1));
        boolean more=rows.size()>limit;var selected=more?rows.subList(0,limit):rows;
        return new DeletedPage(selected.stream().map(d->new DeletedFile(d.id().toString(),d.workspaceId().toString(),d.path(),d.kind(),
                d.versionId()==null?null:d.versionId().toString(),d.revision(),d.deletionOperationId().toString(),d.deletedAt().toString(),d.retainUntil().toString(),d.canRestore())).toList(),
                more?selected.getLast().id().toString():null);
    }
    @Override public Preview preview(FileApi.Caller caller,String file) {
        var actor=new FileData.Actor(FileRules.id(caller.userId()),FileRules.id(caller.spaceId()));
        var value=transactions.execute(s->repository.preview(actor,FileRules.id(file)));
        return new Preview(value.revision(),value.entries(),value.allConfirmed());
    }
    @Override public FileApi.OperationView restore(FileApi.Caller caller,String file,String request,String ifMatch,Restore body) {
        if(body==null||(body.sourceVersionId()==null)==(body.deletionOperationId()==null))throw new FileFailure(400,"INVALID_RESTORE_SOURCE");
        if(body.name()!=null&&(body.name().contains("/")||!FileRules.path(body.name(),false).equals(body.name())))throw new FileFailure(400,"INVALID_FILE_NAME");
        if(request==null)throw new FileFailure(428,"IDEMPOTENCY_KEY_REQUIRED");
        var actor=new FileData.Actor(FileRules.id(caller.userId()),FileRules.id(caller.spaceId()));
        long revision=version(ifMatch);
        String hash=RunnerSignatures.sha256(("RESTORE\n"+file+"\n"+revision+"\n"+JSON.writeValueAsString(body)).getBytes(StandardCharsets.UTF_8));
        if(body.deletionOperationId()!=null) {
            var result=transactions.execute(s->repository.restoreTree(actor,FileRules.id(file),FileRules.id(request),hash,revision,
                    FileRules.id(body.deletionOperationId()),body.targetParentId()==null?null:FileRules.id(body.targetParentId()),body.name()));
            return new FileApi.OperationView(result.id().toString(),result.workspaceId().toString(),null,result.status(),result.revision(),result.errorCode(),List.of("READ"));
        }
        var source=files.version(actor,FileRules.id(file),FileRules.id(body.sourceVersionId()));
        var row=transactions.execute(s->repository.prepareRestore(actor,FileRules.id(file),FileRules.id(request),hash,revision,
                source.id(),body.targetParentId()==null?null:FileRules.id(body.targetParentId()),body.name()));
        if(row.operationId()!=null)return fileApi.operation(caller,row.operationId().toString());
        if("CREATED".equals(row.status())) {
            var owner=java.util.UUID.randomUUID();var transfer=files.beginTransfer(actor,row.id(),owner);
            try {
                assets.copy(source.objectKey(),transfer.objectKey(),transfer.sizeBytes(),transfer.sha256(),()->files.guardTransfer(actor,transfer.id(),owner));
                files.transferVerified(actor,transfer.id(),owner);
            }catch(RuntimeException failure) {
                try{if(failure instanceof FileFailure rejected&&rejected.status()==423)files.transferRejected(actor,transfer.id(),owner);
                    else files.transferUncertain(actor,transfer.id(),owner);}catch(RuntimeException unavailable){failure.addSuppressed(unavailable);}
                throw failure;
            }
        }else if(!"VERIFIED".equals(row.status()))throw new FileFailure(409,"RESTORE_STAGING_PENDING");
        return fileApi.complete(caller,row.id().toString());
    }
}
