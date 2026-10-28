package com.agentplatform.infrastructure.adapter.repository.chat;

import com.agentplatform.domain.chat.*;
import com.agentplatform.domain.chat.CancellationLedger.*;
import com.agentplatform.domain.chat.ChatData.*;
import com.agentplatform.infrastructure.dao.chat.ChatMappers;
import com.agentplatform.infrastructure.dao.po.ControlRows.ControlCallRow;
import java.util.*;

/** Control facts use the same Spring transaction and lock authority as execution writes. */
final class ControlPersistence extends ChatPersistenceSupport implements CancellationLedger {
    ControlPersistence(ChatMappers mappers) { super(mappers); }

    @Override public Reconciliation reconciliation(Actor actor, UUID taskId) {
        return tx(c -> { auth(c,actor); task(c,actor,taskId,false); return projection(actor,taskId); });
    }
    @Override public Reconciliation reconciliationForOperator(UUID taskId) {
        return tx(c -> { var state=c.control().taskState(taskId); if(state==null)throw new ChatFailure(404,"RESOURCE_NOT_FOUND");
            return projection(new Actor(state.userId(),state.spaceId()),taskId); });
    }
    private Reconciliation projection(Actor actor, UUID taskId) {
        var t=task(mappers,actor,taskId,false);var state=mappers.control().taskState(taskId);
        var calls=mappers.control().calls(taskId,actor.spaceId());
        int unknown=(int)calls.stream().filter(row -> "UNKNOWN".equals(row.status())||"RECONCILING".equals(row.reservationState())).count();
        boolean terminal=!"RUNNING".equals(t.status())&&!"QUEUED".equals(t.status());
        boolean hasStop=mappers.control().attemptCount(taskId)==0||mappers.control().latestStopCount(taskId)==1;
        boolean confirmed=!state.live()&&terminal&&hasStop;
        String stage=confirmed?"STOPPED":terminal&&!hasStop?"UNCONFIRMED":!t.cancelRequested()&&t.reasonCode()==null?"NOT_REQUESTED":
                state.overdue()||"EXECUTION_STOP_UNCONFIRMED".equals(t.reasonCode())?"UNCONFIRMED":"STOPPING";
        return new Reconciliation(taskId,stage,confirmed,unknown,calls.stream().map(row -> new CallState(row.id(),row.status(),row.reservationState(),row.inputTokens(),row.outputTokens(),row.costMicros(),row.revision())).toList(),
                !confirmed&&(t.cancelRequested()||"UNCONFIRMED".equals(stage))?"WAIT_FOR_STOP":unknown>0?"CONTACT_OPERATOR_FOR_RECONCILIATION":"NONE",
                !"SUCCEEDED".equals(t.status()),t.version());
    }
    @Override public void stopped(Lease lease,String status,String reason,KnownUsage known,String receiptHash) {
        tx(c -> {
            owned(c,lease,true);
            if(receiptHash==null||!receiptHash.matches("[a-f0-9]{64}"))throw new ChatFailure(409,"STOP_RECEIPT_INVALID");
            WorkspaceToolPersistence.requireStoppedChildren(c,lease.spaceId(),lease.taskId());
            c.control().recordStop(lease,receiptHash);
            if(c.control().stopCount(lease)!=1)throw new ChatFailure(409,"STOP_RECEIPT_MISMATCH");
            if(known!=null) {
                var row=c.control().lockedCall(known.call().id());
                if(row==null||!lease.attemptId().equals(row.attemptId())||!lease.spaceId().equals(row.spaceId()))throw new ChatFailure(409,"CALL_SCOPE_MISMATCH");
                if("RESERVED".equals(row.reservationState())||"RECONCILING".equals(row.reservationState()))
                    settleKnown(row,known.round().inputTokens(),known.round().outputTokens(),known.requestId());
            }
            boolean cancelled=task(c,new Actor(lease.userId(),lease.spaceId()),lease.taskId(),false).cancelRequested();
            String terminal=cancelled?"CANCELLED":status;
            String code=cancelled?"TASK_CANCEL_REQUESTED":reason;
            // Stopping a transport cannot prove the outcome of an already started tool.
            c.ledger().updateToolOperation149(lease.taskId());
            boolean unknownTool=c.recovery().operations(lease.taskId()).stream().anyMatch(operation->Set.of("RUNNING","UNKNOWN").contains(operation.status()));
            if(unknownTool||(c.recovery().unknown(lease.taskId())>0&&!"SUCCEEDED".equals(terminal))){terminal="BLOCKED";code="EXTERNAL_RESULT_UNKNOWN";}
            new ExecutionPersistence(c).finish(lease,terminal,code);
            return null;
        });
    }
    @Override public void stopUnconfirmed(Lease lease) {
        tx(c -> { owned(c,lease,true);if(c.control().markUnconfirmed(lease.taskId())==1)stateEvent(new Actor(lease.userId(),lease.spaceId()),lease.taskId());return null; });
    }
    private void stateEvent(Actor actor,UUID taskId) {
        var t=task(mappers,actor,taskId,false);var payload=new LinkedHashMap<String,Object>();
        payload.put("taskId",taskId.toString());payload.put("status",t.status());payload.put("cancelRequested",t.cancelRequested());payload.put("reasonCode",t.reasonCode());
        event(mappers,actor,t.conversationId(),taskId,"task.state_changed",t.version(),payload);
    }
    @Override public void reconcile(Command command) {
        tx(c -> {
            validate(command);String fingerprint=hash(json(command));
            String previous=c.control().commandHash(command.commandId());
            if(previous!=null){if(!previous.equals(fingerprint))throw new ChatFailure(409,"IDEMPOTENCY_CONFLICT");return null;}
            var hint=c.control().call(command.callId());if(hint==null)throw new ChatFailure(404,"CALL_NOT_FOUND");
            new MyBatisQuotaLedger(c.quota()).lock(hint.userId());
            c.dispatch().updateExecutionSlot87();Actor actor=new Actor(hint.userId(),hint.spaceId());
            conversation(c,actor,hint.conversationId(),true);var t=task(c,actor,hint.taskId(),true);
            if(c.control().taskState(t.id()).live()||"RUNNING".equals(t.status())||"QUEUED".equals(t.status()))throw new ChatFailure(409,"EXECUTION_NOT_STOPPED");
            var row=c.control().lockedCall(command.callId());
            previous=c.control().commandHash(command.commandId());
            if(previous!=null){if(!previous.equals(fingerprint))throw new ChatFailure(409,"IDEMPOTENCY_CONFLICT");return null;}
            if(row.revision()!=command.expectedRevision())throw new ChatFailure(409,"RECONCILIATION_VERSION_CONFLICT");
            if(!"RECONCILING".equals(row.reservationState()))throw new ChatFailure(409,"CALL_ALREADY_RESOLVED");
            if(command.decision()!=Decision.KEEP_UNKNOWN) {
                if("LEGACY_UNKNOWN".equals(row.providerKind()))throw new ChatFailure(409,"HISTORICAL_PROVIDER_UNRESOLVED");
                if(!"PROVIDER_RECORD".equals(command.evidenceKind()))throw new ChatFailure(422,"SUPPLIER_EVIDENCE_REQUIRED");
                if(command.providerRequestId()==null||command.providerRequestId().isBlank()||
                        (row.providerRequestId()!=null&&!row.providerRequestId().equals(command.providerRequestId())))throw new ChatFailure(409,"PROVIDER_EVIDENCE_MISMATCH");
                if(command.decision()==Decision.CONFIRMED_NOT_EXECUTED) {
                    if(daily(c,row.taskId())){
                        new MyBatisQuotaLedger(c.quota()).settle(row.userId(),row.id(),null,null,true);
                        c.accounting().updateBudgetReservation121("RELEASED",row.id());
                    }else{
                        if(!c.quota().reservations(row.id()).isEmpty())new MyBatisQuotaLedger(c.quota()).settle(row.userId(),row.id(),null,null,true);
                        if(c.control().release(row.id(),row.spaceId(),row.userId())!=1)throw new ChatFailure(409,"ACCOUNTING_CONFLICT");
                    }
                    c.control().notExecuted(row.id());
                    if("REAL".equals(row.providerKind()))c.accounting().updateAcceptanceModelMeter123(row.allowance());
                } else {
                    if(command.inputTokens()==null||command.outputTokens()==null)throw new ChatFailure(422,"KNOWN_USAGE_REQUIRED");
                    settleKnown(row,command.inputTokens(),command.outputTokens(),command.providerRequestId());
                }
            }
            if(c.control().revision(row.id(),command.expectedRevision())!=1)throw new ChatFailure(409,"RECONCILIATION_VERSION_CONFLICT");
            c.control().audit(command,row.spaceId(),row.taskId(),fingerprint,json(row),json(c.control().call(row.id())));
            String terminal=t.status(),reason=t.reasonCode();
            if("BLOCKED".equals(terminal)&&c.recovery().unknown(t.id())==0){terminal=t.cancelRequested()?"CANCELLED":"FAILED";reason=t.cancelRequested()?"TASK_CANCEL_REQUESTED":"EXECUTION_INTERRUPTED_RECONCILED";}
            c.conversation().updateTask135(terminal,reason,t.id());stateEvent(actor,t.id());
            audit(c,actor,"MODEL_CALL_RECONCILED",row.id(),com.agentplatform.types.RequestTrace.current());return null;
        });
    }
    private void settleKnown(ControlCallRow row,long input,long output,String request) {
        if("LEGACY_UNKNOWN".equals(row.providerKind()))throw new ChatFailure(409,"HISTORICAL_PROVIDER_UNRESOLVED");
        if(input<0||input>8192||output<0||output>1024)throw new ChatFailure(409,"PROVIDER_USAGE_EXCEEDS_RESERVATION");
        var config=object(row.configJson());
        long inputPrice=((Number)config.getOrDefault("inputPrice",2000000L)).longValue();
        long outputPrice=((Number)config.getOrDefault("outputPrice",8000000L)).longValue();
        long cost=(Math.multiplyExact(input,inputPrice)+Math.multiplyExact(output,outputPrice)+999999)/1000000;
        if(cost>row.allowance())throw new ChatFailure(409,"PROVIDER_USAGE_EXCEEDS_RESERVATION");
        if(mappers.control().settledBudget(row.id())!=1)throw new ChatFailure(409,"ACCOUNTING_CONFLICT");
        mappers.control().settledCall(row.id(),input,output,cost,request);
        if(!mappers.quota().reservations(row.id()).isEmpty())new MyBatisQuotaLedger(mappers.quota()).settle(row.userId(),row.id(),input+output,cost,false);
        if(!daily(mappers,row.taskId())&&mappers.control().settledAccount(row.spaceId(),row.userId(),row.allowance(),input+output,cost)!=1)throw new ChatFailure(409,"ACCOUNTING_CONFLICT");
        if("REAL".equals(row.providerKind()))mappers.accounting().updateAcceptanceModelMeter127(row.allowance(),cost);
        event(mappers,new Actor(row.userId(),row.spaceId()),row.conversationId(),row.taskId(),"usage.updated",row.revision()+1,
                Map.of("callId",row.id().toString(),"inputTokens",input,"outputTokens",output,"costMicros",cost,"estimated",true));
    }
    private static void validate(Command command) {
        if(command==null||command.callId()==null||command.commandId()==null||command.expectedRevision()<0||command.decision()==null||
                command.evidenceReference()==null||command.evidenceHash()==null||!command.evidenceHash().matches("[a-f0-9]{64}")||
                command.operator()==null||!command.operator().matches("[a-zA-Z0-9_.@-]{1,128}")||
                !Set.of("REVIEW","UNSENT_LEDGER","PROVIDER_RECORD").contains(command.evidenceKind()==null?"":command.evidenceKind()))throw new ChatFailure(422,"RECONCILIATION_EVIDENCE_INVALID");
    }
}
