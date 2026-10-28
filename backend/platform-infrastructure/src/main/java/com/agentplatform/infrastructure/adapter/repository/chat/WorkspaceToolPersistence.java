package com.agentplatform.infrastructure.adapter.repository.chat;

import com.agentplatform.domain.chat.*;
import com.agentplatform.domain.chat.ChatData.Lease;
import com.agentplatform.infrastructure.dao.chat.ChatMappers;
import com.agentplatform.infrastructure.dao.po.WorkspaceToolRows;
import com.agentplatform.types.Uuid7;
import com.agentplatform.types.harness.HarnessData.Bundle;
import com.agentplatform.types.harness.HarnessData.Call;
import java.util.*;

/** Version resolution is task-scoped: deploying R4.4 does not rewrite old bundle identities. */
final class WorkspaceToolPersistence extends ChatPersistenceSupport {
    static final UUID BUNDLE=UUID.fromString("0199e000-0000-7000-8000-000000000020");
    WorkspaceToolPersistence(ChatMappers mappers){super(mappers);}
    static boolean enabled(ChatMappers c,UUID space,UUID task) {
        var catalog=c.workspaceTools().catalog(space,task);
        if(catalog==null)return false;
        if(!WorkspaceTools.VERSION.equals(catalog.catalogVersion())||!BUNDLE.equals(catalog.runtimeBundleId()))
            throw new ChatFailure(409,"TOOL_CATALOG_INCOMPATIBLE");
        return true;
    }
    static Bundle bundle(ChatMappers c,UUID space,UUID task){return enabled(c,space,task)?ChatRuntime.workspaceBundle():ChatRuntime.bundle();}
    static UUID bundleId(ChatMappers c,UUID space,UUID task){return enabled(c,space,task)?BUNDLE:ChatPersistenceSupport.BUNDLE;}
    static void resourceGuard(ChatMappers c,Lease lease) {
        new MyBatisQuotaLedger(c.quota()).lock(lease.userId());
        if(c.workspaceTools().lockObjectQuota(lease.spaceId())==null)throw new ChatFailure(409,"OBJECT_QUOTA_UNAVAILABLE");
        MyBatisExecutionLedger.guard(c,lease);
    }
    boolean available(Lease lease){return tx(c->{MyBatisExecutionLedger.guard(c,lease);return enabled(c,lease.spaceId(),lease.taskId());});}
    void prepare(Lease lease,List<Call> calls,String image,String policy){tx(c->{
        if(calls.isEmpty())return null;
        if(calls.size()!=1||!WorkspaceTools.NAMES.contains(calls.getFirst().name()))throw new ChatFailure(422,"TOOL_BATCH_UNSUPPORTED");
        resourceGuard(c,lease);
        var catalog=c.workspaceTools().catalog(lease.spaceId(),lease.taskId());
        if(catalog==null||!enabled(c,lease.spaceId(),lease.taskId())||!lease.userId().equals(catalog.actorId()))
            throw new ChatFailure(403,"WORKSPACE_TOOLS_NOT_AVAILABLE");
        if(c.workspaceTools().lockWorkspace(lease.spaceId(),catalog.workspaceId(),lease.userId())==null)
            throw new ChatFailure(409,"WORKSPACE_NOT_READY");
        Call call=calls.getFirst();var original=c.approvals().operation(lease,call.id());
        String argsHash=hash(MyBatisExecutionLedger.canonicalJson(call));
        if(original==null||!argsHash.equals(original.callHash()))throw new ChatFailure(409,"TOOL_OPERATION_MISMATCH");
        var previous=c.workspaceTools().intent(lease.spaceId(),lease.taskId(),original.id());
        if(previous!=null){if(!argsHash.equals(previous.argsHash()))throw new ChatFailure(409,"TOOL_INTENT_MISMATCH");return null;}
        var permission=c.permissions().permission(lease.spaceId(),lease.taskId());
        if(permission.planMode())throw new ChatFailure(403,"PLAN_NOT_APPROVED");
        if(c.plan().plan(lease.spaceId(),lease.taskId())!=null&&c.workspaceTools().hasAcceptedCurrentPlan(lease.spaceId(),lease.taskId())!=1)
            throw new ChatFailure(403,"PLAN_NOT_APPROVED");
        var resource=c.workspaceTools().resource(lease.spaceId(),catalog.workspaceId(),lease.userId());
        if(resource==null||!"READY".equals(resource.state()))throw new ChatFailure(409,"WORKSPACE_NOT_READY");
        var snapshot=new TreeMap<String,Object>();
        snapshot.put("workspaceId",resource.workspaceId());snapshot.put("actorId",resource.actorId());snapshot.put("spaceId",resource.spaceId());
        snapshot.put("state",resource.state());snapshot.put("contentRevision",resource.contentRevision());snapshot.put("quotaBytes",resource.quotaBytes());snapshot.put("quotaInodes",resource.quotaInodes());
        var review=new TreeMap<String,Object>();review.put("toolName",call.name());
        boolean shell="workspace_shell".equals(call.name());
        switch(call.name()) {
            case "workspace_list" -> {var args=JSON.readValue(json(call.arguments()),WorkspaceTools.ListFiles.class);review.put("path",args.parent());}
            case "workspace_read" -> {
                var args=JSON.readValue(json(call.arguments()),WorkspaceTools.ReadFile.class);
                String target=c.workspaceTools().targetById(lease.spaceId(),catalog.workspaceId(),UUID.fromString(args.fileId()),UUID.fromString(args.versionId()));
                if(target==null||object(target).get("versionId")==null)throw new ChatFailure(404,"FILE_VERSION_NOT_FOUND");
                snapshot.put("target",object(target));review.put("fileId",args.fileId());review.put("versionId",args.versionId());
            }
            case "workspace_write" -> {
                var args=JSON.readValue(json(call.arguments()),WorkspaceTools.WriteFile.class);
                String target=c.workspaceTools().targetByPath(lease.spaceId(),catalog.workspaceId(),args.path());
                snapshot.put("target",target==null?null:object(target));review.put("path",args.path());review.put("mode",args.mode());
                review.put("textSha256",hash(args.text()));review.put("bytes",args.text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            }
            case "workspace_shell" -> {
                var args=JSON.readValue(json(call.arguments()),WorkspaceTools.Shell.class);
                if(image==null||!image.matches("sha256:[a-f0-9]{64}")||policy==null||!policy.matches("[a-f0-9]{64}"))
                    throw new ChatFailure(503,"SHELL_POLICY_UNAVAILABLE");
                snapshot.put("imageId",image);snapshot.put("policyHash",policy);
                review.put("command",args.command());review.put("cwd",args.cwd());review.put("outputs",args.outputs());
                review.put("impact","ENTIRE_BOUND_WORKSPACE");review.put("network","NONE");review.put("stopsAfterEachCall",true);
            }
            default -> throw new ChatFailure(422,"TOOL_NOT_REGISTERED");
        }
        String body=MyBatisExecutionLedger.canonicalJson(snapshot);
        var intent=new WorkspaceToolRows.Intent(original.id(),lease.spaceId(),lease.taskId(),catalog.workspaceId(),lease.userId(),call.name(),
                catalog.catalogVersion(),argsHash,resource.contentRevision(),permission.version(),permission.policyVersion(),hash(body),body,json(review),
                shell?Uuid7.next():null,shell?(resource.environmentVersion()==null?0:resource.environmentVersion()):null,shell?image:null,shell?policy:null,null);
        if(c.workspaceTools().insertIntent(intent)!=1)throw new ChatFailure(409,"TOOL_INTENT_CONFLICT");
        return null;
    });}
    WorkspaceToolLedger.Intent intent(Lease lease,Call call){return tx(c->{
        MyBatisExecutionLedger.guard(c,lease);var original=c.approvals().operation(lease,call.id());
        if(original==null)throw new ChatFailure(409,"TOOL_OPERATION_MISSING");
        var row=c.workspaceTools().intent(lease.spaceId(),lease.taskId(),original.id());
        if(row==null||!row.argsHash().equals(hash(MyBatisExecutionLedger.canonicalJson(call))))throw new ChatFailure(409,"TOOL_INTENT_MISSING");
        return new WorkspaceToolLedger.Intent(row.operationId(),row.spaceId(),row.taskId(),row.workspaceId(),row.toolName(),row.argsHash(),row.contentRevision(),row.permissionVersion(),row.resourceHash(),row.resourceJson(),row.reviewJson(),row.activationRequestId(),row.expectedEnvironmentVersion(),row.expectedImageId(),row.expectedPolicyHash());
    });}
    static RiskPolicy.Result decision(ChatMappers c,Lease lease,Call call) {
        var p=c.permissions().permission(lease.spaceId(),lease.taskId());
        if(p==null||p.workspaceId()==null||!enabled(c,lease.spaceId(),lease.taskId()))
            return new RiskPolicy.Result(com.agentplatform.types.harness.HarnessData.Decision.DENY,"CAPABILITY_NOT_AVAILABLE",RiskPolicy.VERSION);
        UUID operation=c.workspaceTools().operationId(lease.spaceId(),lease.taskId(),call.id());
        String current=operation==null?null:c.workspaceTools().currentResourceHash(lease.userId(),lease.spaceId(),lease.taskId(),operation);
        boolean authorized=c.permissions().workspace(new ChatData.Actor(lease.userId(),lease.spaceId()),p.workspaceId());
        var action=switch(call.name()) {
            case "workspace_list" -> RiskPolicy.Action.LIST;
            case "workspace_read" -> RiskPolicy.Action.READ;
            case "workspace_write" -> RiskPolicy.Action.WRITE;
            case "workspace_shell" -> RiskPolicy.Action.EXECUTE;
            default -> throw new ChatFailure(422,"TOOL_NOT_REGISTERED");
        };
        return new RiskPolicy().decide(new RiskPolicy.Facts(authorized,p.planMode(),true,true,RiskPolicy.Mandatory.NONE,
                com.agentplatform.types.TaskPermissionData.Mode.valueOf(p.mode()),false,false),
                new RiskPolicy.Operation(action,current!=null,true,1,false));
    }
    WorkspaceToolLedger.FileAdmission fileAdmission(Lease lease,Call call) {return tx(c->{
        MyBatisExecutionLedger.guard(c,lease);return c.workspaceTools().fileAdmission(intent(lease,call).operationId());
    });}
    WorkspaceToolLedger.FileAdmission recordFileAdmission(Lease lease,Call call,UUID upload,String failure) {return tx(c->{
        var intent=intent(lease,call);var previous=c.workspaceTools().fileAdmission(intent.operationId());
        if(previous!=null){
            if(!Objects.equals(previous.uploadId(),upload)||!Objects.equals(previous.failureCode(),failure))throw new ChatFailure(409,"TOOL_ADMISSION_CONFLICT");
            return previous;
        }
        if((upload==null)==(failure==null)||failure!=null&&!failure.matches("[A-Z][A-Z0-9_]{1,95}"))throw new ChatFailure(422,"INVALID_TOOL_ADMISSION");
        if(c.workspaceTools().insertFileAdmission(lease,intent.operationId(),upload,failure)!=1)throw new ChatFailure(409,"TOOL_ADMISSION_CONFLICT");
        return c.workspaceTools().fileAdmission(intent.operationId());
    });}
    void bindFileOperation(Lease lease,Call call,UUID operation){tx(c->{
        var intent=intent(lease,call);var previous=c.workspaceTools().fileAdmission(intent.operationId());
        if(previous==null||previous.failureCode()!=null)throw new ChatFailure(409,"TOOL_ADMISSION_MISSING");
        if(previous.fileOperationId()!=null){if(!previous.fileOperationId().equals(operation))throw new ChatFailure(409,"TOOL_JOB_CONFLICT");return null;}
        if(c.workspaceTools().bindFileOperation(intent.operationId(),operation,Uuid7.next())!=1)throw new ChatFailure(409,"TOOL_JOB_CONFLICT");
        return null;
    });}
    boolean fileStopped(Lease lease,Call call){return tx(c->c.workspaceTools().fileStopped(lease,call.id()));}
    WorkspaceToolLedger.ResultRecord result(ChatData.Actor actor,UUID operation){return tx(c->{
        var result=c.workspaceTools().publicResult(actor,operation);if(result==null)throw new ChatFailure(404,"RESOURCE_NOT_FOUND");
        if(!Set.of("SUCCEEDED","FAILED").contains(result.status())||result.text()==null)throw new ChatFailure(409,"TOOL_RESULT_PENDING");
        return result;
    });}
    com.agentplatform.types.WorkspaceOperationData.Page operations(ChatData.Actor actor,UUID task,UUID before,int limit){return tx(c->{
        if(limit<1||limit>50)throw new ChatFailure(400,"INVALID_PAGE_LIMIT");
        var rows=c.workspaceTools().publicOperations(actor,task,before,limit+1);
        boolean more=rows.size()>limit;
        var selected=more?rows.subList(0,limit):rows;
        var items=selected.stream().map(r->new com.agentplatform.types.WorkspaceOperationData.Operation(r.operationId(),r.taskId(),r.toolName(),r.status(),r.phase(),r.summary(),
            r.createdAt(),r.endedAt(),r.errorCode(),r.traceId(),JSON.readValue(r.reviewJson(),com.agentplatform.types.WorkspaceOperationData.Review.class),r.resultAvailable())).toList();
        return new com.agentplatform.types.WorkspaceOperationData.Page(items,more?selected.getLast().operationId():null);
    });}
    void requestStop(Lease lease,Call call,WorkspaceToolLedger.StopReason reason){tx(c->{
        Objects.requireNonNull(reason);
        String digest=hash(MyBatisExecutionLedger.canonicalJson(call));
        c.workspaceTools().requestStop(lease,call.id(),digest,reason.name());
        if(!c.workspaceTools().stopMatches(lease,call.id(),digest))throw new ChatFailure(409,"TOOL_STOP_BINDING_MISMATCH");
        return null;
    });}
    com.agentplatform.domain.workspace.WorkspaceShellData.Request shellRequest(Lease lease,Call call){return tx(c->{
        var intent=intent(lease,call);if(!"workspace_shell".equals(call.name()))throw new ChatFailure(422,"SHELL_TOOL_REQUIRED");
        Long generation=c.workspaceTools().taskGeneration(lease);if(generation==null)throw new ChatFailure(409,"EXECUTION_FENCE_LOST");
        var args=JSON.readValue(json(call.arguments()),WorkspaceTools.Shell.class);
        var parent=new com.agentplatform.domain.workspace.WorkspaceShellData.Parent(intent.operationId(),lease.taskId(),lease.attemptId(),lease.ownerId(),generation,lease.fence(),intent.argsHash(),intent.contentRevision(),intent.permissionVersion());
        return new com.agentplatform.domain.workspace.WorkspaceShellData.Request(com.agentplatform.domain.workspace.WorkspaceShellData.PROTOCOL,parent,new com.agentplatform.domain.workspace.SandboxRuntime.ShellRequest(args.command(),args.cwd(),args.timeoutSeconds()));
    });}
}
