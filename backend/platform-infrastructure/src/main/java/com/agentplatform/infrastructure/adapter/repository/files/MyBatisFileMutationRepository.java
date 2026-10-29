package com.agentplatform.infrastructure.adapter.repository.files;

import com.agentplatform.domain.files.*;
import com.agentplatform.domain.files.FileData.*;
import com.agentplatform.domain.files.FileMutationData.*;
import com.agentplatform.infrastructure.dao.files.*;
import com.agentplatform.infrastructure.dao.workspace.*;
import com.agentplatform.infrastructure.workspace.RunnerSignatures;
import com.agentplatform.types.Uuid7;
import java.util.*;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Lifecycle admission only; execution remains in the existing file queue. */
public final class MyBatisFileMutationRepository implements FileMutationRepository {
    private final FileMutationMapper mutations;
    private final FileMapper files;
    private final WorkspaceMapper workspaces;
    private final EnvironmentPreparationMapper environments;
    private final String image,policy;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    public MyBatisFileMutationRepository(FileMutationMapper mutations,FileMapper files,WorkspaceMapper workspaces,
            EnvironmentPreparationMapper environments,String image,String policy) {
        this.mutations=mutations;this.files=files;this.workspaces=workspaces;this.environments=environments;this.image=image;this.policy=policy;
    }
    private void authorized(Actor actor) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Transaction required");
        if(!workspaces.authorized(new com.agentplatform.domain.workspace.WorkspaceData.Actor(actor.userId(),actor.spaceId())))
            throw new FileFailure(403,"FILE_AUTHORIZATION_REVOKED");
    }
    @Override public Upload prepareRestore(Actor actor,UUID file,UUID request,String hash,long revision,UUID sourceVersion,UUID parent,String name) {
        authorized(actor);var source=files.file(file);
        if(source==null||!source.spaceId().equals(actor.spaceId())||!"FILE".equals(source.kind()))throw new FileFailure(404,"RESOURCE_NOT_FOUND");
        var storage=files.lockStorage(actor.spaceId());if(storage==null||!"ACTIVE".equals(storage.status()))throw new FileFailure(409,"SPACE_STORAGE_UNAVAILABLE");
        environments.lockSlot();var workspace=workspaces.lockWorkspace(actor.spaceId(),source.workspaceId());
        if(workspace==null||!"READY".equals(workspace.state()))throw new FileFailure(409,"WORKSPACE_STORAGE_NOT_READY");
        var previous=files.request(actor,request);
        if(previous!=null){if(!hash.equals(previous.requestHash()))throw new FileFailure(409,"IDEMPOTENCY_CONFLICT");return previous;}
        files.ensureLease(actor.spaceId(),source.workspaceId());var lease=files.lockLease(actor.spaceId(),source.workspaceId());
        if(!"FREE".equals(lease.status())||environments.lifecycleBusy(actor.spaceId(),source.workspaceId())||files.unresolvedEnvironmentUse(source.workspaceId()))throw new FileFailure(423,"WORKSPACE_BUSY");
        source=files.file(file);if(source.revision()!=revision)throw new FileFailure(412,"FILE_VERSION_CONFLICT");
        boolean missing=!mutations.active(file)||"MISSING".equals(source.collectionState());
        if(!missing&&!"CONFIRMED".equals(source.collectionState()))throw new FileFailure(409,"FILE_UNSEALED_CHANGES");
        var old=files.version(actor.spaceId(),file,sourceVersion);if(old==null)throw new FileFailure(404,"RESOURCE_NOT_FOUND");
        String target=source.relativePath();Long parentRevision=null;
        if(name!=null||parent!=null) {
            String folder="";
            if(parent!=null){var p=files.file(parent);if(p==null||!p.spaceId().equals(actor.spaceId())||!p.workspaceId().equals(source.workspaceId())||!mutations.active(parent)||!"DIRECTORY".equals(p.kind()))throw new FileFailure(409,"FILE_TARGET_DIRECTORY_INVALID");folder=p.relativePath();parentRevision=p.revision();}
            target=FileRules.path(folder.isEmpty()?(name==null?source.name():name):folder+"/"+(name==null?source.name():name),false);
        }
        var occupied=files.path(actor.spaceId(),source.workspaceId(),target);
        if(occupied!=null&&!occupied.id().equals(file))throw new FileFailure(409,"FILE_NAME_CONFLICT");
        if(files.reserve(actor.spaceId(),old.sizeBytes())!=1)throw new FileFailure(409,"FILE_VERSION_QUOTA_EXCEEDED");
        UUID upload=Uuid7.next(),version=Uuid7.next(),asset=Uuid7.next(),operation=Uuid7.next();
        String key="spaces/"+actor.spaceId()+"/file-version/"+asset+"/"+version+"/"+Uuid7.next();
        var row=new Upload(upload,actor.spaceId(),source.workspaceId(),actor.userId(),request,hash,target,"REPLACE",file,source.currentVersionId(),revision,
                version,asset,key,old.sizeBytes(),old.sha256(),"CREATED",null,null,null,null,1);
        files.insertAsset(row);files.insertUpload(row);files.insertReservation(row);
        var manifest=new TreeMap<String,Object>();manifest.put("sourceMissing",missing);manifest.put("expectedSha256",missing?null:source.sha256());
        manifest.put("sourceVersionId",sourceVersion);manifest.put("fileId",file);
        String json=JSON.writeValueAsString(manifest),digest=RunnerSignatures.sha256(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mutations.restoreUpload(new Restore(upload,operation,actor.spaceId(),source.workspaceId(),file,sourceVersion,source.relativePath(),missing,json,digest,parent,parentRevision));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void beforeCommit(boolean readOnly){authorized(actor);}});
        return files.upload(upload);
    }
    @Override public Request request(Actor actor,UUID file,UUID request) {
        authorized(actor);var row=files.file(file);
        if(row==null||!row.spaceId().equals(actor.spaceId()))throw new FileFailure(404,"RESOURCE_NOT_FOUND");
        var existing=mutations.request(actor,request);
        if(existing!=null&&existing.rootFileId().equals(file))return new Request(existing.operationId(),null,files.operation(existing.operationId()).status());
        var upload=files.request(actor,request);
        if(upload==null||!upload.fileId().equals(file)||files.restore(upload.id())==null)throw new FileFailure(404,"RESOURCE_NOT_FOUND");
        return new Request(upload.operationId(),upload.id(),upload.status());
    }
    @Override public List<Deleted> deleted(Actor actor,UUID workspace,UUID before,int limit) {
        authorized(actor);var row=workspaces.workspace(actor.spaceId(),workspace);
        if(row==null)throw new FileFailure(404,"RESOURCE_NOT_FOUND");
        var result=mutations.deleted(actor.spaceId(),workspace,before,limit);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void beforeCommit(boolean readOnly){authorized(actor);}});
        return result;
    }
    @Override public Preview preview(Actor actor,UUID file) {
        authorized(actor);var value=mutations.preview(actor.spaceId(),file);
        if(value==null)throw new FileFailure(404,"RESOURCE_NOT_FOUND");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void beforeCommit(boolean readOnly){authorized(actor);}});
        return value;
    }
    @Override public Operation restoreTree(Actor actor,UUID file,UUID request,String hash,long revision,UUID deletion,UUID parent,String name) {
        authorized(actor);var root=files.file(file);
        if(root==null||!root.spaceId().equals(actor.spaceId())||!"DIRECTORY".equals(root.kind()))throw new FileFailure(404,"RESOURCE_NOT_FOUND");
        var storage=files.lockStorage(actor.spaceId());if(storage==null||!"ACTIVE".equals(storage.status()))throw new FileFailure(409,"SPACE_STORAGE_UNAVAILABLE");
        environments.lockSlot();var workspace=workspaces.lockWorkspace(actor.spaceId(),root.workspaceId());
        if(workspace==null||!"READY".equals(workspace.state()))throw new FileFailure(409,"WORKSPACE_STORAGE_NOT_READY");
        var previous=mutations.request(actor,request);
        if(previous!=null){if(!previous.requestHash().equals(hash))throw new FileFailure(409,"IDEMPOTENCY_CONFLICT");return files.operation(previous.operationId());}
        files.ensureLease(actor.spaceId(),root.workspaceId());var lease=files.lockLease(actor.spaceId(),root.workspaceId());
        if(!"FREE".equals(lease.status())||environments.lifecycleBusy(actor.spaceId(),root.workspaceId())||files.unresolvedEnvironmentUse(root.workspaceId()))throw new FileFailure(423,"WORKSPACE_BUSY");
        root=files.file(file);var origin=mutations.intentById(deletion);
        if(root.revision()!=revision||mutations.active(file)||origin==null||!origin.spaceId().equals(actor.spaceId())
                ||!origin.workspaceId().equals(root.workspaceId())||!origin.rootFileId().equals(file)||!"DELETE".equals(origin.kind()))throw new FileFailure(412,"FILE_VERSION_CONFLICT");
        var cleanup=files.cleanupOperation(deletion);
        if(cleanup==null||!"COMMITTED".equals(cleanup.status()))throw new FileFailure(423,"FILE_CLEANUP_UNCONFIRMED");
        String target=root.relativePath();Long parentRevision=null;
        if(name!=null||parent!=null) {
            String directory="";
            if(parent!=null){var p=files.file(parent);if(p==null||!p.spaceId().equals(actor.spaceId())||!p.workspaceId().equals(root.workspaceId())||!mutations.active(parent)||!"DIRECTORY".equals(p.kind()))throw new FileFailure(409,"FILE_TARGET_DIRECTORY_INVALID");directory=p.relativePath();parentRevision=p.revision();}
            target=FileRules.path(directory.isEmpty()?(name==null?root.name():name):directory+"/"+(name==null?root.name():name),false);
        }
        if(!mutations.tree(actor.spaceId(),root.workspaceId(),target).isEmpty())throw new FileFailure(409,"FILE_NAME_CONFLICT");
        var rows=mutations.deletedTree(actor.spaceId(),root.workspaceId(),deletion);
        if(rows.size()!=origin.entryCount()||rows.size()>1000)throw new FileFailure(409,"FILE_RESTORE_TREE_CHANGED");
        long total=0;var targets=new ArrayList<TreeFile>();var publicManifest=new ArrayList<Map<String,Object>>();UUID operation=Uuid7.next();
        for(var row:rows) {
            String suffix=row.relativePath().substring(origin.sourcePath().length());
            var descriptor=new TreeMap<String,Object>();descriptor.put("path",suffix.isEmpty()?"":suffix.substring(1));descriptor.put("kind",row.kind());
            UUID version=null,asset=null;String key=null,oldKey=null;Long bytes=null;String sha=null;
            if("FILE".equals(row.kind())) {
                var old=files.version(actor.spaceId(),row.id(),row.currentVersionId());if(old==null)throw new FileFailure(404,"RESOURCE_NOT_FOUND");
                version=Uuid7.next();asset=Uuid7.next();key="spaces/"+actor.spaceId()+"/file-version/"+asset+"/"+version+"/"+Uuid7.next();
                oldKey=old.objectKey();bytes=old.sizeBytes();sha=old.sha256();total=Math.addExact(total,bytes);
                descriptor.put("bytes",bytes);descriptor.put("sha256",sha);
            }
            publicManifest.add(descriptor);
            targets.add(new TreeFile(operation,actor.spaceId(),root.workspaceId(),row.id(),row.kind(),row.relativePath(),target+suffix,row.revision(),
                    row.currentVersionId(),row.currentVersionId(),version,asset,key,oldKey,bytes,sha));
        }
        if(total>2147483648L||files.reserve(actor.spaceId(),total)!=1)throw new FileFailure(409,"FILE_VERSION_QUOTA_EXCEEDED");
        String manifest=JSON.writeValueAsString(publicManifest),manifestHash=RunnerSignatures.sha256(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var op=new Operation(operation,actor.spaceId(),root.workspaceId(),actor.userId(),"RESTORE_TREE",null,Uuid7.next(),null,1,null,null,null,"PENDING","NOT_STARTED",null,1,image,policy,null,null,null,null);
        files.insertOperation(op);
        mutations.treeIntent(new Intent(operation,actor.spaceId(),root.workspaceId(),actor.userId(),request,hash,"RESTORE_TREE",file,origin.sourcePath(),target,revision,manifest,manifestHash,rows.size(),null,parent,parentRevision),deletion);
        for(var row:targets){if(row.assetId()!=null)mutations.treeAsset(row,actor.userId());mutations.treeItem(row);}
        mutations.treeReservation(operation,actor.spaceId(),total);files.insertOutbox(Uuid7.next(),op);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void beforeCommit(boolean readOnly){authorized(actor);}});
        return files.operation(operation);
    }
    @Override public Operation move(Actor actor,UUID file,UUID request,String hash,long revision,UUID parent,String name) {
        return admit(actor,file,request,hash,revision,parent,name,"MOVE");
    }
    @Override public Operation delete(Actor actor,UUID file,UUID request,String hash,long revision) {
        return admit(actor,file,request,hash,revision,null,null,"DELETE");
    }
    private Operation admit(Actor actor,UUID file,UUID request,String hash,long revision,UUID parent,String name,String kind) {
        authorized(actor);
        var source=files.file(file);
        if(source==null||!source.spaceId().equals(actor.spaceId()))throw new FileFailure(404,"RESOURCE_NOT_FOUND");
        var storage=files.lockStorage(actor.spaceId());
        if(storage==null||!"ACTIVE".equals(storage.status()))throw new FileFailure(409,"SPACE_STORAGE_UNAVAILABLE");
        environments.lockSlot();
        var workspace=workspaces.lockWorkspace(actor.spaceId(),source.workspaceId());
        if(workspace==null||!"READY".equals(workspace.state()))throw new FileFailure(409,"WORKSPACE_STORAGE_NOT_READY");
        var previous=mutations.request(actor,request);
        if(previous!=null) {
            if(!previous.requestHash().equals(hash))throw new FileFailure(409,"IDEMPOTENCY_CONFLICT");
            return files.operation(previous.operationId());
        }
        if(environments.lifecycleBusy(actor.spaceId(),source.workspaceId()))throw new FileFailure(423,"WORKSPACE_BUSY");
        files.ensureLease(actor.spaceId(),source.workspaceId());
        var lease=files.lockLease(actor.spaceId(),source.workspaceId());
        if(!"FREE".equals(lease.status())||files.unresolvedEnvironmentUse(source.workspaceId()))throw new FileFailure(423,"WORKSPACE_BUSY");
        source=files.file(file);
        if(!mutations.active(file)||source.revision()!=revision)throw new FileFailure(412,"FILE_VERSION_CONFLICT");
        String directory="";
        Long parentRevision=null;
        if(parent!=null) {
            var destination=files.file(parent);
            if(destination==null||!destination.spaceId().equals(actor.spaceId())||!destination.workspaceId().equals(source.workspaceId())
                    ||!mutations.active(parent)||!"DIRECTORY".equals(destination.kind())||!"CONFIRMED".equals(destination.collectionState()))
                throw new FileFailure(409,"FILE_TARGET_DIRECTORY_INVALID");
            directory=destination.relativePath();
            parentRevision=destination.revision();
        }
        String target="MOVE".equals(kind)?FileRules.path(directory.isEmpty()?name:directory+"/"+name,false):null;
        if(target!=null&&(target.equals(source.relativePath())||target.startsWith(source.relativePath()+"/")))throw new FileFailure(409,"FILE_MOVE_INTO_SELF");
        if(target!=null&&!mutations.tree(actor.spaceId(),source.workspaceId(),target).isEmpty())throw new FileFailure(409,"FILE_NAME_CONFLICT");
        var rows=mutations.tree(actor.spaceId(),source.workspaceId(),source.relativePath());
        if(rows.isEmpty()||rows.size()>1000)throw new FileFailure(409,"FILE_BATCH_TOO_LARGE");
        var items=new ArrayList<Item>();var physical=new ArrayList<Map<String,Object>>();
        for(var row:rows) {
            if(!"CONFIRMED".equals(row.collectionState())||("FILE".equals(row.kind())&&(row.currentVersionId()==null||row.sha256()==null||row.sizeBytes()==null)))
                throw new FileFailure(409,"FILE_UNSEALED_CHANGES");
            String suffix=row.relativePath().substring(source.relativePath().length());
            String relative=suffix.isEmpty()?"":suffix.substring(1);
            var descriptor=new TreeMap<String,Object>();descriptor.put("path",relative);descriptor.put("kind",row.kind());
            if("FILE".equals(row.kind())){descriptor.put("bytes",row.sizeBytes());descriptor.put("sha256",row.sha256());}
            physical.add(descriptor);
            items.add(new Item(row.id(),row.relativePath(),target==null?null:target+suffix,row.kind(),row.revision(),row.currentVersionId(),row.sha256(),row.sizeBytes()));
        }
        String manifest=JSON.writeValueAsString(physical),manifestHash=RunnerSignatures.sha256(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID operation=Uuid7.next();
        var row=new Operation(operation,actor.spaceId(),source.workspaceId(),actor.userId(),kind,null,Uuid7.next(),null,1,null,null,null,
                "PENDING","NOT_STARTED",null,1,image,policy,null,null,null,null);
        files.insertOperation(row);
        mutations.intent(new Intent(operation,actor.spaceId(),source.workspaceId(),actor.userId(),request,hash,kind,file,
                source.relativePath(),target,revision,manifest,manifestHash,items.size(),null,parent,parentRevision));
        for(int i=0;i<items.size();i++)mutations.item(operation,actor.spaceId(),source.workspaceId(),i,items.get(i));
        files.insertOutbox(Uuid7.next(),row);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void beforeCommit(boolean readOnly){authorized(actor);}
        });
        return files.operation(operation);
    }
}
