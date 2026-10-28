package com.agentplatform.infrastructure.adapter.repository.chat;

import com.agentplatform.domain.chat.*;
import com.agentplatform.domain.chat.ChatData.*;
import com.agentplatform.types.Uuid7;
import com.agentplatform.types.harness.HarnessData;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import com.agentplatform.infrastructure.dao.chat.ChatMappers;
import com.agentplatform.infrastructure.dao.po.ChatProjections.*;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Facade preserving the public domain port; responsibilities live in focused adapters. */
public final class MyBatisChatRepository implements ChatRepository {
    public static final UUID BUNDLE = ChatPersistenceSupport.BUNDLE;
    private final ControlPersistence controls;
    private final PlanPersistence plans;
    private final ChatMappers mappers;
    private final RecoveryPersistence recovery;
    private final ConversationPersistence conversations;
    private final ConfigurationPersistence configurations;
    private final DispatchPersistence dispatches;
    private final ExecutionPersistence executions;
    private final CheckpointPersistence checkpoints;
    public MyBatisChatRepository(ChatMappers mappers) {
        this(mappers,false);
    }
    public MyBatisChatRepository(ChatMappers mappers,boolean workspaceTools) {
        this.mappers=mappers;plans=new PlanPersistence(mappers);
        controls = new ControlPersistence(mappers);
        recovery = new RecoveryPersistence(mappers);
        conversations = new ConversationPersistence(mappers,workspaceTools);
        configurations = new ConfigurationPersistence(mappers);
        dispatches = new DispatchPersistence(mappers);
        executions = new ExecutionPersistence(mappers);
        checkpoints = new CheckpointPersistence(mappers);
    }
    @Override public java.util.List<com.agentplatform.types.WorkspaceOperationData.Artifact> workspaceOperationArtifacts(Actor actor,UUID operation){return new WorkspaceToolPersistence(mappers).tx(c->{new WorkspaceToolPersistence(c).result(actor,operation);return c.workspaceTools().publicArtifacts(actor,operation);});}
    @Override public HarnessData.Bundle executionBundle(Lease lease) {
        return new WorkspaceToolPersistence(mappers).tx(c->{MyBatisExecutionLedger.guard(c,lease);return WorkspaceToolPersistence.bundle(c,lease.spaceId(),lease.taskId());});
    }
    @Override public boolean workspaceToolTask(Lease lease){return new WorkspaceToolPersistence(mappers).available(lease);}
    @Override public WorkspaceToolLedger.ResultRecord workspaceOperationResult(Actor actor,UUID operation){return new WorkspaceToolPersistence(mappers).result(actor,operation);}
    @Override public com.agentplatform.types.WorkspaceOperationData.Page workspaceOperations(Actor actor,UUID task,UUID before,int limit){task(actor,task);return new WorkspaceToolPersistence(mappers).operations(actor,task,before,limit);}
    @Override public void prepareWorkspaceTools(Lease lease,List<HarnessData.Call> calls,String image,String policy){new WorkspaceToolPersistence(mappers).prepare(lease,calls,image,policy);}
    @Override public WorkspaceToolLedger.Intent workspaceToolIntent(Lease lease,HarnessData.Call call){return new WorkspaceToolPersistence(mappers).intent(lease,call);}
    @Override public WorkspaceToolLedger.FileAdmission workspaceFileAdmission(Lease lease,HarnessData.Call call){return new WorkspaceToolPersistence(mappers).fileAdmission(lease,call);}
    @Override public WorkspaceToolLedger.FileAdmission recordWorkspaceFileAdmission(Lease lease,HarnessData.Call call,UUID upload,String failure){return new WorkspaceToolPersistence(mappers).recordFileAdmission(lease,call,upload,failure);}
    @Override public void bindWorkspaceFileOperation(Lease lease,HarnessData.Call call,UUID operation){new WorkspaceToolPersistence(mappers).bindFileOperation(lease,call,operation);}
    @Override public boolean workspaceFileStopped(Lease lease,HarnessData.Call call){return new WorkspaceToolPersistence(mappers).fileStopped(lease,call);}
    @Override public void requestWorkspaceToolStop(Lease lease,HarnessData.Call call,WorkspaceToolLedger.StopReason reason){new WorkspaceToolPersistence(mappers).requestStop(lease,call,reason);}
    @Override public com.agentplatform.domain.workspace.WorkspaceShellData.Request workspaceShellRequest(Lease lease,HarnessData.Call call){return new WorkspaceToolPersistence(mappers).shellRequest(lease,call);}
    @Override public List<Model> models(Actor a) { return conversations.models(a); }
    @Override public Conversation create(Actor a,UUID key,String title) { return conversations.create(a, key, title); }
    @Override public List<Conversation> conversations(Actor a,UUID before,int limit) { return conversations.conversations(a, before, limit); }
    @Override public Conversation conversation(Actor a,UUID id) { return conversations.conversation(a, id); }
    @Override public Snapshot snapshot(Actor a,UUID id) { return conversations.snapshot(a, id); }
    @Override public Task submit(Actor a,UUID convo,UUID key,UUID model,String text) { return conversations.submit(a, convo, key, model, text); }
    @Override public Task submitConfigured(Actor a,UUID convo,UUID key,UUID model,String source,String text) { return conversations.submitConfigured(a, convo, key, model, source, text); }
    @Override public Task task(Actor a,UUID id) { return conversations.task(a, id); }
    @Override public Task cancel(Actor a,UUID id,UUID key) { return conversations.cancel(a, id, key); }
    @Override public List<Event> events(Actor a,UUID id,long after) { return conversations.events(a, id, after); }
    @Override public List<Usage> usage(Actor a,UUID id) { return conversations.usage(a, id); }
    @Override public List<Publish> publications(UUID relay) { return dispatches.publications(relay); }
    @Override public void published(UUID relay,Publish item,boolean success) { dispatches.published(relay, item, success); }
    @Override public void wakeDeferred() { dispatches.wakeDeferred(); }
    @Override public Lease claim(Envelope e,UUID owner) { return dispatches.claim(e, owner); }
    @Override public void quarantine(String reason) { dispatches.quarantine(reason); }
    @Override public void check(Lease l) { executions.check(l); }
    @Override public void heartbeat(Lease l) { executions.heartbeat(l); }
    @Override public List<HarnessData.Message> history(Lease l) { return executions.history(l); }
    @Override public void delta(Lease l,String text) { executions.delta(l, text); }
    @Override public Call beginCall(Lease l,boolean real) { return executions.beginCall(l, real); }
    @Override public void startCall(Lease l,Call call,boolean real) { executions.startCall(l, call, real); }
    @Override public void permitAcceptanceCall(Lease l,Call call,UUID run){
        ChatPersistenceSupport.owned(mappers,l,false);mappers.executionPolicy().createAcceptanceRun(run);
        if(mappers.executionPolicy().reserveAcceptance(run)!=1)throw new ChatFailure(429,"ACCEPTANCE_REQUEST_LIMIT");
        mappers.executionPolicy().recordAcceptanceCall(run,call.id());
    }
    @Override public void settle(Lease l,Call call,HarnessData.Round round,String requestId,String error,boolean real) { executions.settle(l, call, round, requestId, error, real); }
    @Override public Credential credential(Lease l,UUID call) { return executions.credential(l, call); }
    @Override public void finish(Lease l,String status,String reason) { executions.finish(l, status, reason); }
    @Override public UUID beginTurn(Lease l) { return checkpoints.beginTurn(l); }
    @Override public void commitRound(Lease l,UUID id,HarnessData.Turn turn,ExecutionLedger.CallResult accepted) { checkpoints.commitRound(l, id, turn, accepted); }
    @Override public void startTool(Lease l,HarnessData.Call call) { checkpoints.startTool(l, call); }
    @Override public void commitTool(Lease l,HarnessData.Call call,HarnessData.Observation result) { checkpoints.commitTool(l, call, result); }
    @Override public HarnessData.StateSlot state(Lease l,String key) { return checkpoints.state(l, key); }
    @Override public long stateCas(Lease l,String key,long version,byte[] body) { return checkpoints.stateCas(l, key, version, body); }
    @Override public Set<String> stateKeys(Lease l) { return checkpoints.stateKeys(l); }
    @Override public void stateDelete(Lease l,String key) { checkpoints.stateDelete(l, key); }
    @Override public UUID sealCheckpoint(Lease l,HarnessData.Bundle bundle) { return checkpoints.sealCheckpoint(l, bundle); }
    @Override public List<HarnessData.Message> checkpointHistory(Lease l) { return checkpoints.checkpointHistory(l); }
    @Override public void validateCheckpoint(Lease l,UUID id,HarnessData.Bundle bundle) { checkpoints.validateCheckpoint(l, id, bundle); }
    @Override public void restoreCheckpoint(Lease l,UUID id,HarnessData.Bundle bundle) { checkpoints.restoreCheckpoint(l, id, bundle); }
    @Override public void recoverStopped(ExecutionLedger.StoppedOwner proof) { dispatches.recoverStopped(proof); }
    @Override public void recoverStopped(UUID owner) { dispatches.recoverStopped(owner); }
    @Override public List<Credential> recoveryCredentials() { return configurations.recoveryCredentials(); }
    @Override public UUID importCredential(Credential k) { return configurations.importCredential(k); }
    @Override public void revokeCredential(UUID id) { configurations.revokeCredential(id); }
    @Override public void bind(UUID model,Actor a,UUID credential,String source) { configurations.bind(model, a, credential, source); }
    @Override public First first(Actor a,UUID key,String title,UUID model,String source,String text) { return conversations.first(a, key, title, model, source, text); }
    @Override public Map<String,Object> preferences(Actor a) { return configurations.preferences(a); }
    @Override public Map<String,Object> preferences(Actor a,long expected,Map<String,Object> patch) { return configurations.preferences(a, expected, patch); }
    @Override public Map<String,Object> effective(Actor a) { return configurations.effective(a); }
    @Override public Map<String,Object> configuration(Actor a,UUID id) { return configurations.configuration(a, id); }
    @Override public Map<String,Object> credentialMetadata(Actor a) { return configurations.credentialMetadata(a); }
    @Override public Map<String,Object> replaceUserCredential(Actor a,long expected,Credential encrypted) { return configurations.replaceUserCredential(a, expected, encrypted); }
    @Override public String modelName(Lease l) { return configurations.modelName(l); }
    @Override public Map<String,Object> diagnostics(Actor a,UUID id) { return conversations.diagnostics(a, id); }
    @Override public Reconciliation reconciliation(Actor actor,UUID task) { return controls.reconciliation(actor,task); }
    @Override public Reconciliation reconciliationForOperator(UUID task) { return controls.reconciliationForOperator(task); }
    @Override public void reconcile(Command command) { controls.reconcile(command); }
    @Override public void stopped(Lease lease,String status,String reason,KnownUsage known,String receiptHash) { controls.stopped(lease,status,reason,known,receiptHash); }
    @Override public void stopUnconfirmed(Lease lease) { controls.stopUnconfirmed(lease); }
    @Override public RecoveryLedger.Startup executionStartup(Lease l) { return recovery.executionStartup(l); }
    @Override public UUID commitCheckpoint(Lease l,HarnessData.Bundle b,HarnessData.CheckpointDraft d) { return recovery.commitCheckpoint(l,b,d); }
    @Override public RecoveryLedger.Recovery recovery(Actor a,UUID id) { return recovery.recovery(a,id); }
    @Override public RecoveryLedger.Recovery recoveryForOperator(UUID id) { return recovery.recoveryForOperator(id); }
    @Override public RecoveryLedger.Recovery restore(RecoveryLedger.RestoreCommand command) { return recovery.restore(command); }
    @Override public boolean ready() { return conversations.ready(); }

    @Override public com.agentplatform.types.PlanInteractionData.Plan plan(Actor a,UUID t){return plans.plan(a,t);}
    @Override public com.agentplatform.types.PlanInteractionData.Plan editPlan(Actor a,UUID t,long v,com.agentplatform.types.PlanInteractionData.Edit e){return plans.editPlan(a,t,v,e);}
    @Override public List<com.agentplatform.types.PlanInteractionData.Pending> pending(Actor a,UUID c){return plans.pending(a,c);}
    @Override public com.agentplatform.types.PlanInteractionData.Accepted answer(Actor a,UUID t,UUID i,UUID k,com.agentplatform.types.PlanInteractionData.Answer v){return plans.answer(a,t,i,k,v);}
    @Override public void stageControls(Lease l,List<HarnessData.Call> c){plans.stageControls(l,c);}
    @Override public HarnessData.Decision controlDecision(Lease l,HarnessData.Call c){return plans.controlDecision(l,c);}
    @Override public HarnessData.Observation executeControl(Lease l,HarnessData.Call c){return plans.executeControl(l,c);}
    @Override public void waitingStopped(Lease l,String receipt){plans.waitingStopped(l,receipt);}
    @Override public List<HarnessData.Approval> controlApprovals(Lease l){return plans.controlApprovals(l);}
    @Override public void stageToolApprovals(Lease l,List<HarnessData.Call> calls){new ApprovalPersistence(mappers).stageToolApprovals(l,calls);}
    @Override public List<HarnessData.Approval> toolApprovals(Lease l){return new ApprovalPersistence(mappers).toolApprovals(l);}
    @Override public boolean planMode(Lease l){return plans.planMode(l);}
    @Override public void consumeOptional(Lease l){plans.consumeOptional(l);}
    @Override public List<QuotaData.View> quota(Actor a){ChatPersistenceSupport.auth(mappers,a);return new MyBatisQuotaLedger(mappers.quota()).view(a.userId());}
    @Override public long configureQuota(QuotaData.Change change){return new MyBatisQuotaLedger(mappers.quota()).configure(change);}
    @Override public First firstPlan(Actor a,UUID k,String title,UUID model,String source,String text,boolean plan){return conversations.firstPlan(a,k,title,model,source,text,plan);}
    @Override public Task submitPlan(Actor a,UUID conversation,UUID k,UUID model,String source,String text,boolean plan){return conversations.submitPlan(a,conversation,k,model,source,text,plan);}

    @Override public com.agentplatform.types.TaskPermissionData.View permissions(Actor a,UUID t){return new PermissionPersistence(mappers).permissions(a,t);}
    @Override public com.agentplatform.types.TaskPermissionData.Scopes permissionScopes(Actor a,UUID t){return new PermissionPersistence(mappers).scopes(a,t);}
    @Override public com.agentplatform.types.TaskPermissionData.Scopes permissionScopes(Actor a,UUID t,String cursor){return new PermissionPersistence(mappers).scopes(a,t,cursor);}
    @Override public com.agentplatform.types.TaskPermissionData.View permissionMode(Actor a,UUID t,UUID k,long v,com.agentplatform.types.TaskPermissionData.Mode m){return new PermissionPersistence(mappers).mode(a,t,k,v,m);}
    @Override public com.agentplatform.types.TaskPermissionData.View grant(Actor a,UUID t,UUID k,long v,com.agentplatform.types.TaskPermissionData.CreateGrant g){return new PermissionPersistence(mappers).grant(a,t,k,v,g);}
    @Override public com.agentplatform.types.TaskPermissionData.View revokeGrant(Actor a,UUID t,UUID g,UUID k,long v){return new PermissionPersistence(mappers).revoke(a,t,g,k,v);}
    @Override public HarnessData.Decision operationDecision(Lease l,HarnessData.Call c){return PermissionPersistence.decision(mappers,l,c,true);}
    @Override public First firstAuthorized(Actor a,UUID k,String title,UUID model,String source,String text,boolean plan,UUID w,com.agentplatform.types.TaskPermissionData.Mode m){return conversations.firstAuthorized(a,k,title,model,source,text,plan,w,m);}
    @Override public Task submitAuthorized(Actor a,UUID conversation,UUID k,UUID model,String source,String text,boolean plan,UUID w,com.agentplatform.types.TaskPermissionData.Mode m){return conversations.submitAuthorized(a,conversation,k,model,source,text,plan,w,m);}
}
