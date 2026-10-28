package com.agentplatform.infrastructure.adapter.repository.chat;

import com.agentplatform.domain.chat.*;
import com.agentplatform.domain.chat.ChatData.*;
import com.agentplatform.infrastructure.dao.chat.ChatMappers;
import com.agentplatform.infrastructure.dao.po.ApprovalRows.*;
import com.agentplatform.infrastructure.dao.po.PermissionRows.PermissionRow;
import com.agentplatform.infrastructure.dao.po.PlanRows.*;
import com.agentplatform.types.PlanInteractionData;
import com.agentplatform.types.PlanInteractionData.ToolApprovalPayload;
import com.agentplatform.types.Uuid7;
import com.agentplatform.types.harness.HarnessData.*;
import com.agentplatform.types.harness.HarnessData.Call;
import java.util.*;

/** Approval is a fact attached to the original operation, never an alternative execution loop. */
final class ApprovalPersistence extends ChatPersistenceSupport implements ApprovalLedger {
    ApprovalPersistence(ChatMappers mappers) { super(mappers); }

    private static PermissionRow permission(ChatMappers c, UUID space, UUID task) {
        var p=c.permissions().permission(space,task);
        if(p==null)throw new ChatFailure(404,"RESOURCE_NOT_FOUND");
        return p;
    }
    private static String resourceHash(ChatMappers c,PermissionRow p) {
        if(p.workspaceId()==null)return hash("UNBOUND_WORKSPACE");
        String resource=c.approvals().resource(p);
        return resource==null?null:hash(MyBatisExecutionLedger.canonicalJson(object(resource)));
    }
    private static String resourceHash(ChatMappers c,PermissionRow p,UUID operation) {
        if(WorkspaceToolPersistence.enabled(c,p.spaceId(),p.taskId()))
            return operation==null?null:c.workspaceTools().currentResourceHash(p.actorId(),p.spaceId(),p.taskId(),operation);
        return resourceHash(c,p);
    }
    private static String resourceHash(ChatMappers c,PermissionRow p,Call call) {
        return resourceHash(c,p,c.workspaceTools().operationId(p.spaceId(),p.taskId(),call.id()));
    }
    private static String fingerprint(ChatMappers c,PermissionRow p,Call call) {
        var identity=new TreeMap<String,Object>();identity.put("tool",call.name());identity.put("arguments",call.arguments());
        identity.put("workspace",p.workspaceId());identity.put("resource",resourceHash(c,p,call));identity.put("policy",p.policyVersion());
        return hash(MyBatisExecutionLedger.canonicalJson(identity));
    }
    private static boolean stale(ChatMappers c,BindingRow b) {
        var p=permission(c,b.spaceId(),b.taskId());
        return !Objects.equals(b.resourceHash(),resourceHash(c,p,b.operationId())) || b.permissionVersion()!=p.version()
                || !b.policyVersion().equals(p.policyVersion()) || !RiskPolicy.VERSION.equals(p.policyVersion())
                || !Objects.equals(b.workspaceId(),p.workspaceId()) || !b.actorId().equals(p.actorId()) || p.planMode()
                || p.cancelRequested() || !Set.of("RUNNING","WAITING_INPUT","QUEUED").contains(p.status())
                || !b.expiresAt().isAfter(p.now())
                || (p.workspaceId()!=null&&!c.permissions().workspace(new Actor(p.actorId(),p.spaceId()),p.workspaceId()));
    }
    private static OperationRow requireOperation(ChatMappers c,Lease l,Call call) {
        var operation=c.approvals().operation(l,call.id());
        if(operation==null||!operation.callHash().equals(hash(MyBatisExecutionLedger.canonicalJson(call))))
            throw new ChatFailure(409,"APPROVAL_OPERATION_MISMATCH");
        return operation;
    }
    private static Call original(OperationRow operation) { return JSON.readValue(operation.callJson(),Call.class); }

    @Override public void stageToolApprovals(Lease lease,List<Call> calls) { tx(c->{
        MyBatisExecutionLedger.guard(c,lease);
        if(calls.isEmpty())return null;
        if(calls.size()!=1||PlanControls.NAMES.contains(calls.getFirst().name()))throw new ChatFailure(422,"TOOL_BATCH_UNSUPPORTED");
        Call call=calls.getFirst();var operation=requireOperation(c,lease,call);
        if(!"PREPARED".equals(operation.status()))return null;
        if(PermissionPersistence.decision(c,lease,call,false)!=Decision.ASK)return null;
        var existing=c.approvals().binding(lease.spaceId(),lease.taskId(),call.id());
        if(existing!=null){
            if(!existing.callHash().equals(operation.callHash())||!existing.operationId().equals(operation.id()))throw new ChatFailure(409,"APPROVAL_OPERATION_MISMATCH");
            if(existing.sourceAttemptId().equals(lease.attemptId())||"ANSWERED".equals(existing.interactionStatus()))return null;
            if(!"OPEN".equals(existing.interactionStatus())||c.recovery().hasStop(lease.taskId(),existing.sourceAttemptId())!=1)
                throw new ChatFailure(409,"EXECUTION_STOP_NOT_CONFIRMED");
            c.plan().closeInteraction(existing.interactionId(),"SUPERSEDED");
            var old=c.plan().interaction(lease.spaceId(),lease.taskId(),existing.interactionId());
            event(c,new Actor(lease.userId(),lease.spaceId()),lease.conversationId(),lease.taskId(),"interaction.resolved",old.version(),
                    Map.of("taskId",lease.taskId().toString(),"interactionId",old.id().toString(),"decision","SUPERSEDED","version",old.version()));
        }
        var p=permission(c,lease.spaceId(),lease.taskId());String resource=resourceHash(c,p,operation.id());
        if(resource==null)throw new ChatFailure(409,"APPROVAL_TARGET_UNAVAILABLE");
        UUID interaction=Uuid7.next();
        c.plan().insertInteraction(interaction,lease.spaceId(),lease.taskId(),lease.attemptId(),call.id(),"TOOL_APPROVAL",false,json(Map.of("toolName",call.name())),null,null);
        String summary=WorkspaceTools.NAMES.contains(call.name())
                ? "workspace_shell".equals(call.name())?"Run the reviewed command in the entire bound workspace. It may change or delete workspace files; the sandbox stops after this call.":"Perform the reviewed operation in the task's fixed workspace."
                :"Run one protected deterministic acceptance operation.";
        c.approvals().bind(interaction,lease,operation,p,resource,fingerprint(c,p,call),summary,
                WorkspaceTools.NAMES.contains(call.name())?"CONFIRMATION_REQUIRED":"MANDATORY_ASK");
        var row=c.plan().interaction(lease.spaceId(),lease.taskId(),interaction);
        event(c,new Actor(lease.userId(),lease.spaceId()),lease.conversationId(),lease.taskId(),"interaction.opened",row.version(),
                Map.of("interactionId",interaction.toString(),"taskId",lease.taskId().toString(),"kind","TOOL_APPROVAL","optional",false,"version",row.version(),"expiresAt",row.expiresAt().toString()));
        return null;
    }); }

    ToolApprovalPayload projection(InteractionRow interaction) {
        var b=mappers.approvals().interaction(interaction.id());
        if(b==null)throw new ChatFailure(409,"APPROVAL_BINDING_MISSING");
        var o=mappers.approvals().metadata(b.spaceId(),b.taskId(),b.operationId());
        if(o==null)throw new ChatFailure(409,"APPROVAL_OPERATION_MISMATCH");
        boolean changed=stale(mappers,b)||!"PREPARED".equals(o.status());
        Object toolName=object(interaction.payloadJson()).get("toolName");
        String safeToolName=toolName instanceof String name&&!name.isBlank()?name:"Protected operation";
        com.agentplatform.types.WorkspaceOperationData.Review review=null;
        if(WorkspaceToolPersistence.enabled(mappers,b.spaceId(),b.taskId())) {
            // Use the same allowlisted, actor-scoped projection as ToolCard. The API
            // never receives grants to private call_json, credentials or SDK state.
            UUID before=null;
            while(true) {
                var page=mappers.workspaceTools().publicOperations(new Actor(b.actorId(),b.spaceId()),b.taskId(),before,51);
                var row=page.stream().filter(value->b.operationId().equals(value.operationId())).findFirst();
                if(row.isPresent()){review=JSON.readValue(row.get().reviewJson(),com.agentplatform.types.WorkspaceOperationData.Review.class);break;}
                if(page.size()<51)throw new ChatFailure(409,"APPROVAL_OPERATION_MISMATCH");
                before=page.getLast().operationId();
            }
        }
        return new ToolApprovalPayload(b.operationId(),safeToolName,b.summary(),b.reasonCode(),b.workspaceId(),null,
                b.workspaceId()==null?null:b.resourceHash(),b.callHash(),changed,
                changed?List.of(PlanInteractionData.Decision.DENY):List.of(PlanInteractionData.Decision.ALLOW_ONCE,PlanInteractionData.Decision.DENY),b.permissionVersion(),b.policyVersion(),review);
    }
    void validateAnswer(InteractionRow interaction,PlanInteractionData.Answer answer) {
        if(!Set.of(PlanInteractionData.Decision.ALLOW_ONCE,PlanInteractionData.Decision.DENY).contains(answer.decision())
                ||!answer.text().isEmpty()||!answer.optionIds().isEmpty())throw new ChatFailure(422,"INTERACTION_ANSWER_INVALID");
        var b=mappers.approvals().interaction(interaction.id());
        if(b==null||!b.sourceAttemptId().equals(interaction.sourceAttemptId())||!b.callId().equals(interaction.callId()))throw new ChatFailure(409,"APPROVAL_BINDING_MISMATCH");
        var operation=mappers.approvals().metadata(b.spaceId(),b.taskId(),b.operationId());
        if(operation==null||!operation.callHash().equals(b.callHash())||!"PREPARED".equals(operation.status()))throw new ChatFailure(409,"APPROVAL_OPERATION_NOT_PREPARED");
        if(answer.decision()==PlanInteractionData.Decision.ALLOW_ONCE&&stale(mappers,b))throw new ChatFailure(412,"APPROVAL_STALE");
    }
    private static AnswerRow answerForResume(ChatMappers c,Lease lease,BindingRow b) {
        var answer=c.plan().answer(b.interactionId());var resume=c.plan().resume(lease.attemptId());
        if(answer==null||resume==null||!answer.id().equals(resume.answerId())||!b.callId().equals(resume.callId())
                ||!b.sourceAttemptId().equals(resume.sourceAttemptId())||!answer.requestHash().equals(resume.answerHash()))return null;
        var checkpoint=c.recovery().checkpoint(resume.checkpointId(),lease.taskId());
        if(checkpoint==null||!"WAITING_APPROVAL".equals(checkpoint.phase())||!b.sourceAttemptId().equals(checkpoint.attemptId()))throw new ChatFailure(409,"APPROVAL_CHECKPOINT_BINDING_MISMATCH");
        var stored=JSON.readValue(answer.contentJson(),PlanInteractionData.Answer.class);
        String digest=hash(MyBatisExecutionLedger.canonicalJson(List.of(lease.taskId(),b.interactionId(),stored)));
        if(!digest.equals(answer.requestHash())||!Set.of("ALLOW_ONCE","DENY").contains(answer.decision()))throw new ChatFailure(409,"APPROVAL_ANSWER_BINDING_MISMATCH");
        return answer;
    }
    @Override public List<Approval> toolApprovals(Lease lease) { return tx(c->{
        owned(c,lease,false);var resume=c.plan().resume(lease.attemptId());if(resume==null)return List.of();
        var b=c.approvals().binding(lease.spaceId(),lease.taskId(),resume.callId());if(b==null)return List.of();
        var answer=answerForResume(c,lease,b);if(answer==null)throw new ChatFailure(409,"APPROVAL_ANSWER_BINDING_MISMATCH");
        var operation=requireOperation(c,lease,original(c.approvals().original(b.spaceId(),b.taskId(),b.operationId())));
        boolean allowed="ALLOW_ONCE".equals(answer.decision())&&!stale(c,b)&&"PREPARED".equals(operation.status());
        return List.of(new Approval(original(operation),allowed));
    }); }

    static Decision apply(ChatMappers c,Lease lease,Call call,Decision base) {
        if(base==Decision.DENY||PlanControls.NAMES.contains(call.name()))return base;
        var p=permission(c,lease.spaceId(),lease.taskId());
        if(c.approvals().deniedFingerprint(lease.spaceId(),lease.taskId(),fingerprint(c,p,call))>0)return Decision.DENY;
        var binding=c.approvals().binding(lease.spaceId(),lease.taskId(),call.id());
        if(binding==null)return base;
        if(!binding.callHash().equals(hash(MyBatisExecutionLedger.canonicalJson(call))))return Decision.DENY;
        var answer=answerForResume(c,lease,binding);
        if(answer==null)return base;
        if("DENY".equals(answer.decision())||stale(c,binding))return Decision.DENY;
        return "ALLOW_ONCE".equals(answer.decision())?Decision.ALLOW:base;
    }
    static void consume(ChatMappers c,Lease lease,Call call) {
        var b=c.approvals().binding(lease.spaceId(),lease.taskId(),call.id());if(b==null)return;
        var answer=answerForResume(c,lease,b);
        if(answer==null||!"ALLOW_ONCE".equals(answer.decision())||stale(c,b))throw new ChatFailure(409,"APPROVAL_NOT_VALID");
        if(b.workspaceId()!=null&&!WorkspaceTools.NAMES.contains(call.name())){
            String locked=c.approvals().lockResource(permission(c,lease.spaceId(),lease.taskId()));
            if(locked==null||!b.resourceHash().equals(hash(MyBatisExecutionLedger.canonicalJson(object(locked)))))throw new ChatFailure(409,"APPROVAL_STALE");
        }
        var operation=requireOperation(c,lease,call);
        if(!b.operationId().equals(operation.id())||!b.callHash().equals(operation.callHash())||c.approvals().consumption(operation.id())!=null)
            throw new ChatFailure(409,"APPROVAL_ALREADY_CONSUMED");
        c.approvals().consume(lease,b,answer.id(),"ALLOW_ONCE");
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
            @Override public void beforeCommit(boolean readOnly){
                if(!b.expiresAt().isAfter(c.recovery().now()))throw new ChatFailure(409,"APPROVAL_EXPIRED");
                if(stale(c,b))throw new ChatFailure(409,"APPROVAL_STALE");
            }
        });
    }
    static void denied(ChatMappers c,Lease lease,Call call,Observation result) {
        MyBatisExecutionLedger.guard(c,lease);var operation=requireOperation(c,lease,call);
        if(!call.id().equals(result.callId())||!call.name().equals(result.name())||result.status()!=OperationStatus.DENIED)throw new ChatFailure(409,"OPERATION_RESULT_MISMATCH");
        String body=MyBatisExecutionLedger.canonicalJson(result),digest=hash(body);
        if("DENIED".equals(operation.status())&&digest.equals(operation.resultHash()))return;
        if(!"PREPARED".equals(operation.status())||PermissionPersistence.decision(c,lease,call,false)!=Decision.DENY)throw new ChatFailure(409,"OPERATION_DENIAL_NOT_AUTHORIZED");
        var b=c.approvals().binding(lease.spaceId(),lease.taskId(),call.id());
        if(b!=null){
            var answer=answerForResume(c,lease,b);
            if(answer!=null){
                if(c.approvals().consumption(operation.id())!=null)throw new ChatFailure(409,"APPROVAL_ALREADY_CONSUMED");
                c.approvals().consume(lease,b,answer.id(),"DENY".equals(answer.decision())?"DENY":"STALE_DENIED");
            }
        }
        if(c.approvals().deny(lease,operation.id(),body,digest)!=1)throw new ChatFailure(409,"OPERATION_DENIAL_CONFLICT");
    }
}
