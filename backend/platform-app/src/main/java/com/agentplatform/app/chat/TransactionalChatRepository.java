package com.agentplatform.app.chat;

import com.agentplatform.domain.chat.ChatRepository;
import com.agentplatform.domain.chat.ChatData.*;
import com.agentplatform.domain.chat.ChatFailure;
import com.agentplatform.domain.chat.ExecutionLedger.*;
import com.agentplatform.types.harness.HarnessData;
import com.agentplatform.types.harness.HarnessData.Turn;
import com.agentplatform.types.harness.HarnessData.Observation;
import com.agentplatform.types.harness.HarnessData.StateSlot;
import com.agentplatform.types.harness.HarnessData.Bundle;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Application atomic boundaries; network and provider calls never run inside these transactions. */
public final class TransactionalChatRepository implements ChatRepository {
    private final ChatRepository delegate;
    private final TransactionTemplate transactions;
    private final TransactionTemplate snapshots;
    private final SnapshotQueryService replay;
    public TransactionalChatRepository(ChatRepository delegate, TransactionTemplate transactions) {
        this.delegate = delegate;
        this.transactions = transactions;
        snapshots = new TransactionTemplate(transactions.getTransactionManager());
        snapshots.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        replay = new SnapshotQueryService(delegate, transactions.getTransactionManager());
    }
    private <T> T tx(TransactionTemplate template, Supplier<T> action) {
        try { return template.execute(status -> action.get()); }
        catch (DuplicateKeyException conflict) { throw new ChatFailure(409, "RESOURCE_CONFLICT"); }
        catch (ConcurrencyFailureException conflict) { throw new ChatFailure(409, "CONCURRENT_CHANGE"); }
        catch (DataAccessException | TransactionException unavailable) {
            var failure = new ChatFailure(503, "CHAT_STORAGE_UNAVAILABLE");
            failure.initCause(unavailable);
            throw failure;
        }
    }
    @Override public com.agentplatform.domain.chat.RecoveryLedger.Startup executionStartup(Lease l) { return tx(transactions, () -> delegate.executionStartup(l)); }
    @Override public UUID commitCheckpoint(Lease l,Bundle b,HarnessData.CheckpointDraft d) { return tx(transactions, () -> delegate.commitCheckpoint(l,b,d)); }
    @Override public com.agentplatform.domain.chat.RecoveryLedger.Recovery recovery(Actor a,UUID id) { return tx(snapshots, () -> delegate.recovery(a,id)); }
    @Override public com.agentplatform.domain.chat.RecoveryLedger.Recovery recoveryForOperator(UUID id) { return tx(snapshots, () -> delegate.recoveryForOperator(id)); }
    @Override public com.agentplatform.domain.chat.RecoveryLedger.Recovery restore(com.agentplatform.domain.chat.RecoveryLedger.RestoreCommand command) { return tx(transactions, () -> delegate.restore(command)); }
    @Override public List<Model> models(Actor actor) { return tx(transactions, () -> delegate.models(actor)); }
    @Override public Conversation create(Actor actor, UUID key, String title) { return tx(transactions, () -> delegate.create(actor, key, title)); }
    @Override public Conversation conversation(Actor actor, UUID id) { return tx(transactions, () -> delegate.conversation(actor, id)); }
    @Override public List<Conversation> conversations(Actor actor, UUID before, int limit) { return tx(transactions, () -> delegate.conversations(actor, before, limit)); }
    @Override public Snapshot snapshot(Actor actor, UUID conversation) { return replay.snapshot(actor, conversation); }
    @Override public Task submit(Actor actor, UUID conversation, UUID key, UUID model, String text) { return tx(transactions, () -> delegate.submit(actor, conversation, key, model, text)); }
    @Override public Task task(Actor actor, UUID task) { return tx(transactions, () -> delegate.task(actor, task)); }
    @Override public Reconciliation reconciliation(Actor actor,UUID task) { return tx(snapshots, () -> delegate.reconciliation(actor,task)); }
    @Override public Reconciliation reconciliationForOperator(UUID task) { return tx(snapshots, () -> delegate.reconciliationForOperator(task)); }
    @Override public void reconcile(Command command) { tx(transactions, () -> { delegate.reconcile(command); return null; }); }
    @Override public void stopped(Lease lease,String status,String reason,KnownUsage known,String receiptHash) { tx(transactions, () -> { delegate.stopped(lease,status,reason,known,receiptHash); return null; }); }
    @Override public void stopUnconfirmed(Lease lease) { tx(transactions, () -> { delegate.stopUnconfirmed(lease); return null; }); }
    @Override public Task cancel(Actor actor, UUID task, UUID key) { return tx(transactions, () -> delegate.cancel(actor, task, key)); }
    @Override public List<Event> events(Actor actor, UUID conversation, long after) { return replay.events(actor, conversation, after); }
    @Override public List<Usage> usage(Actor actor, UUID task) { return tx(transactions, () -> delegate.usage(actor, task)); }
    @Override public List<Publish> publications(UUID relay) { return tx(transactions, () -> delegate.publications(relay)); }
    @Override public void published(UUID relay, Publish item, boolean success) { tx(transactions, () -> { delegate.published(relay, item, success); return null; }); }
    @Override public void wakeDeferred() { tx(transactions, () -> { delegate.wakeDeferred(); return null; }); }
    @Override public Lease claim(Envelope message, UUID owner) { return tx(transactions, () -> delegate.claim(message, owner)); }
    @Override public void quarantine(String reason) { tx(transactions, () -> { delegate.quarantine(reason); return null; }); }
    @Override public void heartbeat(Lease lease) { tx(transactions, () -> { delegate.heartbeat(lease); return null; }); }
    @Override public void check(Lease lease) { tx(transactions, () -> { delegate.check(lease); return null; }); }
    @Override public List<HarnessData.Message> history(Lease lease) { return tx(transactions, () -> delegate.history(lease)); }
    @Override public void delta(Lease lease, String text) { tx(transactions, () -> { delegate.delta(lease, text); return null; }); }
    @Override public Call beginCall(Lease lease, boolean real) { return tx(transactions, () -> delegate.beginCall(lease, real)); }
    @Override public void startCall(Lease lease, Call call, boolean real) { tx(transactions, () -> { delegate.startCall(lease, call, real); return null; }); }
    @Override public void permitAcceptanceCall(Lease l,Call call,UUID run){tx(transactions,()->{delegate.permitAcceptanceCall(l,call,run);return null;});}
    @Override public void settle(Lease lease, Call call, HarnessData.Round round, String requestId, String error, boolean real) { tx(transactions, () -> { delegate.settle(lease, call, round, requestId, error, real); return null; }); }
    @Override public Credential credential(Lease lease, UUID callId) { return tx(transactions, () -> delegate.credential(lease, callId)); }
    @Override public void finish(Lease lease, String status, String reason) { tx(transactions, () -> { delegate.finish(lease, status, reason); return null; }); }
    @Override public void recoverStopped(UUID owner) { tx(transactions, () -> { delegate.recoverStopped(owner); return null; }); }
    @Override public List<Credential> recoveryCredentials() { return tx(transactions, () -> delegate.recoveryCredentials()); }
    @Override public UUID importCredential(Credential credential) { return tx(transactions, () -> delegate.importCredential(credential)); }
    @Override public void revokeCredential(UUID credential) { tx(transactions, () -> { delegate.revokeCredential(credential); return null; }); }
    @Override public void bind(UUID model, Actor actor, UUID credential, String sourcePolicy) { tx(transactions, () -> { delegate.bind(model, actor, credential, sourcePolicy); return null; }); }
    @Override public First first(Actor actor, UUID key, String title, UUID model, String source, String text) { return tx(transactions, () -> delegate.first(actor, key, title, model, source, text)); }
    @Override public Task submitConfigured(Actor actor, UUID conversation, UUID key, UUID model, String source, String text) { return tx(transactions, () -> delegate.submitConfigured(actor, conversation, key, model, source, text)); }
    @Override public java.util.Map<String,Object> diagnostics(Actor actor, UUID task) { return tx(transactions, () -> delegate.diagnostics(actor, task)); }
    @Override public java.util.Map<String,Object> preferences(Actor actor) { return tx(transactions, () -> delegate.preferences(actor)); }
    @Override public java.util.Map<String,Object> preferences(Actor actor, long expected, Map<String, Object> patch) { return tx(transactions, () -> delegate.preferences(actor, expected, patch)); }
    @Override public java.util.Map<String,Object> effective(Actor actor) { return tx(snapshots, () -> delegate.effective(actor)); }
    @Override public java.util.Map<String,Object> configuration(Actor actor, UUID task) { return tx(snapshots, () -> delegate.configuration(actor, task)); }
    @Override public java.util.Map<String,Object> credentialMetadata(Actor actor) { return tx(transactions, () -> delegate.credentialMetadata(actor)); }
    @Override public java.util.Map<String,Object> replaceUserCredential(Actor actor, long expected, Credential encrypted) { return tx(transactions, () -> delegate.replaceUserCredential(actor, expected, encrypted)); }
    @Override public String modelName(Lease lease) { return tx(transactions, () -> delegate.modelName(lease)); }
    @Override public boolean ready() { try { return tx(transactions, delegate::ready); } catch (ChatFailure unavailable) { return false; } }
    @Override public UUID beginTurn(Lease lease) { return tx(transactions, () -> delegate.beginTurn(lease)); }
    @Override public void commitRound(Lease lease, UUID turnId, Turn turn, CallResult accepted) { tx(transactions, () -> { delegate.commitRound(lease, turnId, turn, accepted); return null; }); }
    @Override public void startTool(Lease lease, com.agentplatform.types.harness.HarnessData.Call call) { tx(transactions, () -> { delegate.startTool(lease, call); return null; }); }
    @Override public void commitTool(Lease lease, com.agentplatform.types.harness.HarnessData.Call call, Observation result) { tx(transactions, () -> { delegate.commitTool(lease, call, result); return null; }); }
    @Override public StateSlot state(Lease lease, String key) { return tx(transactions, () -> delegate.state(lease, key)); }
    @Override public long stateCas(Lease lease, String key, long version, byte[] bytes) { return tx(transactions, () -> delegate.stateCas(lease, key, version, bytes)); }
    @Override public Set<String> stateKeys(Lease lease) { return tx(transactions, () -> delegate.stateKeys(lease)); }
    @Override public void stateDelete(Lease lease, String key) { tx(transactions, () -> { delegate.stateDelete(lease, key); return null; }); }
    @Override public UUID sealCheckpoint(Lease lease, Bundle bundle) { return tx(transactions, () -> delegate.sealCheckpoint(lease, bundle)); }
    @Override public java.util.List<HarnessData.Message> checkpointHistory(Lease lease) { return tx(transactions, () -> delegate.checkpointHistory(lease)); }
    @Override public void validateCheckpoint(Lease lease, UUID checkpoint, Bundle bundle) { tx(transactions, () -> { delegate.validateCheckpoint(lease, checkpoint, bundle); return null; }); }
    @Override public void restoreCheckpoint(Lease lease, UUID checkpoint, Bundle bundle) { tx(transactions, () -> { delegate.restoreCheckpoint(lease, checkpoint, bundle); return null; }); }
    @Override public void recoverStopped(StoppedOwner proof) { tx(transactions, () -> { delegate.recoverStopped(proof); return null; }); }

    @Override public com.agentplatform.types.PlanInteractionData.Plan plan(Actor a,UUID t){return tx(snapshots,()->delegate.plan(a,t));}
    @Override public com.agentplatform.types.PlanInteractionData.Plan editPlan(Actor a,UUID t,long v,com.agentplatform.types.PlanInteractionData.Edit e){return tx(transactions,()->delegate.editPlan(a,t,v,e));}
    @Override public List<com.agentplatform.types.PlanInteractionData.Pending> pending(Actor a,UUID c){return tx(snapshots,()->delegate.pending(a,c));}
    @Override public com.agentplatform.types.PlanInteractionData.Accepted answer(Actor a,UUID t,UUID i,UUID k,com.agentplatform.types.PlanInteractionData.Answer v){return tx(transactions,()->delegate.answer(a,t,i,k,v));}
    @Override public void stageControls(Lease l,List<HarnessData.Call> c){tx(transactions,()->{delegate.stageControls(l,c);return null;});}
    @Override public HarnessData.Decision controlDecision(Lease l,HarnessData.Call c){return tx(transactions,()->delegate.controlDecision(l,c));}
    @Override public HarnessData.Observation executeControl(Lease l,HarnessData.Call c){return tx(transactions,()->delegate.executeControl(l,c));}
    @Override public void waitingStopped(Lease l,String r){tx(transactions,()->{delegate.waitingStopped(l,r);return null;});}
    @Override public List<HarnessData.Approval> controlApprovals(Lease l){return tx(transactions,()->delegate.controlApprovals(l));}
    @Override public boolean planMode(Lease l){return tx(transactions,()->delegate.planMode(l));}
    @Override public void consumeOptional(Lease l){tx(transactions,()->{delegate.consumeOptional(l);return null;});}
    @Override public List<com.agentplatform.domain.chat.QuotaData.View> quota(Actor a){return tx(transactions,()->delegate.quota(a));}
    @Override public long configureQuota(com.agentplatform.domain.chat.QuotaData.Change change){return tx(transactions,()->delegate.configureQuota(change));}
    @Override public First firstPlan(Actor a,UUID k,String title,UUID model,String source,String text,boolean plan){return tx(transactions,()->delegate.firstPlan(a,k,title,model,source,text,plan));}
    @Override public Task submitPlan(Actor a,UUID conversation,UUID k,UUID model,String source,String text,boolean plan){return tx(transactions,()->delegate.submitPlan(a,conversation,k,model,source,text,plan));}

    @Override public com.agentplatform.types.TaskPermissionData.View permissions(Actor a,UUID t){return tx(snapshots,()->delegate.permissions(a,t));}
    @Override public com.agentplatform.types.TaskPermissionData.Scopes permissionScopes(Actor a,UUID t){return tx(snapshots,()->delegate.permissionScopes(a,t));}
    @Override public com.agentplatform.types.TaskPermissionData.Scopes permissionScopes(Actor a,UUID t,String cursor){return tx(snapshots,()->delegate.permissionScopes(a,t,cursor));}
    @Override public com.agentplatform.types.TaskPermissionData.View permissionMode(Actor a,UUID t,UUID k,long v,com.agentplatform.types.TaskPermissionData.Mode m){return tx(transactions,()->delegate.permissionMode(a,t,k,v,m));}
    @Override public com.agentplatform.types.TaskPermissionData.View grant(Actor a,UUID t,UUID k,long v,com.agentplatform.types.TaskPermissionData.CreateGrant g){return tx(transactions,()->delegate.grant(a,t,k,v,g));}
    @Override public com.agentplatform.types.TaskPermissionData.View revokeGrant(Actor a,UUID t,UUID g,UUID k,long v){return tx(transactions,()->delegate.revokeGrant(a,t,g,k,v));}
    @Override public HarnessData.Decision operationDecision(Lease l,HarnessData.Call c){return tx(transactions,()->delegate.operationDecision(l,c));}
    @Override public First firstAuthorized(Actor a,UUID k,String title,UUID model,String source,String text,boolean plan,UUID w,com.agentplatform.types.TaskPermissionData.Mode m){return tx(transactions,()->delegate.firstAuthorized(a,k,title,model,source,text,plan,w,m));}
    @Override public Task submitAuthorized(Actor a,UUID conversation,UUID k,UUID model,String source,String text,boolean plan,UUID w,com.agentplatform.types.TaskPermissionData.Mode m){return tx(transactions,()->delegate.submitAuthorized(a,conversation,k,model,source,text,plan,w,m));}
}
