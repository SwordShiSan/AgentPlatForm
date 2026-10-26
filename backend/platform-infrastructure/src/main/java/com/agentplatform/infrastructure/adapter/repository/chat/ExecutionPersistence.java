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

/** Execution persistence use cases over typed MyBatis collaborators. */
final class ExecutionPersistence extends ChatPersistenceSupport {
    ExecutionPersistence(ChatMappers mappers) { super(mappers); }

    public void check(Lease l){tx(c->{owned(c,l,false);authorizeCredential(c,l);activeModel(c,l);return null;});}
    public void heartbeat(Lease l){tx(c->{owned(c,l,false);authorizeCredential(c,l);activeModel(c,l);c.dispatch().updateExecutionSlot111();c.dispatch().updateExecutionDispatch112(l.taskId());c.dispatch().updateConversationLease113(l.attemptId());return null;});}
    public List<HarnessData.Message> history(Lease l){return tx(c->{
        owned(c,l,false);List<HarnessData.Message> messages=new ArrayList<>();
        String instructions=c.executionPolicy().policy(l.taskId()).planMode()
                ? "You are in text-only Plan mode. Use only the provided platform CONTROL tools. Ask platform_ask_user only when essential information is missing. Save a concrete plan with platform_plan_write, then request review with platform_plan_review in a SEPARATE model round. At most one CONTROL invocation per round. Do not begin execution before the explicit review result. Approval permits text continuation only; no file, shell, workspace or business tools exist. Never invent a user answer. A revision or rejection means discuss and prepare a revised plan for another explicit review."
                : "You are a helpful assistant. Answer ordinary text questions. No tools or filesystem access are available.";
        messages.add(HarnessData.Message.text(HarnessData.Role.SYSTEM,instructions));
        { var rows37 = c.conversation().selectMessage37(l.spaceId(), l.conversationId(), l.taskId());
            long estimate=512;for (var row37 : rows37){String text=String.valueOf(blocks(row37.value2()).getFirst().get("text"));estimate+=text.getBytes(StandardCharsets.UTF_8).length+32;if(estimate>6144)throw new ChatFailure(422,"CONTEXT_REQUIRED_TOO_LARGE");messages.add(HarnessData.Message.text(HarnessData.Role.valueOf(row37.value1()),text));}
         }return List.copyOf(messages);
    });}
    public void delta(Lease l,String text){if(text.isEmpty())return;tx(c->{
        owned(c,l,false);authorizeCredential(c,l);activeModel(c,l);String prior;long version;
        { var rows36 = c.conversation().selectMessage36(l.assistantId()); var row36 = rows36.isEmpty() ? null : rows36.getFirst(); prior=String.valueOf(blocks(row36.value1()).getFirst().get("text"));version=row36.value2()+1; }
        if(prior.getBytes(StandardCharsets.UTF_8).length+text.getBytes(StandardCharsets.UTF_8).length>65536)throw new ChatFailure(422,"MODEL_OUTPUT_LIMIT");
        c.conversation().updateMessage114(json(textBlocks(l.assistantId(),prior+text)), version, l.assistantId());
        event(c,new Actor(l.userId(),l.spaceId()),l.conversationId(),l.taskId(),"message.delta",version,Map.of("messageId",l.assistantId().toString(),"blockId",l.assistantId().toString(),"offset",prior.codePointCount(0,prior.length()),"text",text));return null;
    });}
    public Call beginCall(Lease l,boolean real){return tx(c->{
        owned(c,l,false);authorizeCredential(c,l);activeModel(c,l);RecoveryPersistence.requireTime(c,l);UUID turn=MyBatisExecutionLedger.beginTurn(c,l);int ordinal;
        { var rows35 = c.accounting().selectModelCall35(l.taskId()); var row35 = rows35.isEmpty() ? null : rows35.getFirst(); ordinal=row35.value1()+1; }
        long allowance=callAllowance(c,l);
        if(!daily(c,l.taskId())&&c.conversation().updateTask115(allowance, l.taskId(), allowance)!=1)throw new ChatFailure(429,"QUOTA_EXCEEDED");
        UUID id=Uuid7.next();String price=(String)frozen(c,l).get("pricingVersion");
        c.accounting().insertModelCall116(id, l.spaceId(), l.taskId(), l.attemptId(), ordinal, price, turn);
        c.accounting().insertBudgetReservation117(id, l.taskId(), allowance);
        if(daily(c,l.taskId()))new MyBatisQuotaLedger(c.quota()).reserve(l.userId(),id,9216,allowance);
        return new Call(id,ordinal,price);
    });}
    public void startCall(Lease l,Call call,boolean real){tx(c->{owned(c,l,false);
        if(daily(c,l.taskId()))new MyBatisQuotaLedger(c.quota()).revalidate(l.userId(),call.id());
        authorizeCredential(c,l);activeModel(c,l);RecoveryPersistence.requireTime(c,l);
        long allowance=callAllowance(c,l);
        if(real&&c.accounting().updateAcceptanceModelMeter118(allowance)!=1)throw new ChatFailure(503,"ACCEPTANCE_METER_UNAVAILABLE");
        if(c.accounting().updateModelCall119(call.id(), l.taskId())!=1)throw new ChatFailure(409,"CALL_ALREADY_STARTED");c.control().providerKind(call.id(),real?"REAL":"DETERMINISTIC");return null;
    });}
    public void settle(Lease l,Call call,HarnessData.Round round,String requestId,String error,boolean real){tx(c->{settleCall(c,l,call,round,requestId,error,real);return null;});}
    public Credential credential(Lease l,UUID call){return tx(c->{
        owned(c,l,false);
        authorizeCredential(c,l);
        { var rows33 = c.accounting().selectModelCall33(call, l.taskId(), l.attemptId(), l.spaceId()); if(rows33.isEmpty())throw new ChatFailure(409,"CALL_NOT_FOUND"); }
        { var rows32 = c.configuration().selectCredential32(l.credentialId(), l.credentialRevision()); var row32 = rows32.isEmpty() ? null : rows32.getFirst();
            if(row32 == null)throw new ChatFailure(403,"CREDENTIAL_REVOKED");String scope=row32.scopeType();UUID space=row32.spaceId(),owner=row32.ownerUserId();
            if("USER".equals(scope)&&(!l.spaceId().equals(space)||!l.userId().equals(owner)))throw new ChatFailure(403,"CREDENTIAL_SCOPE_REJECTED");
            c.accounting().insertCredentialAccessAudit128(Uuid7.next(), l.credentialId(), call);
            return new Credential(l.credentialId(),scope,space,owner,row32.ciphertext(),row32.nonce(),row32.keyVersion(),row32.revision());
         }
    });}
    public void finish(Lease l,String status,String reason){tx(c->{
        owned(c,l,true);if(c.control().stopCount(l)!=1)throw new ChatFailure(409,"EXECUTION_STOP_NOT_CONFIRMED");if("SUCCEEDED".equals(status)){owned(c,l,false);authorizeCredential(c,l);activeModel(c,l);}if(!Set.of("SUCCEEDED","FAILED","CANCELLED","BLOCKED").contains(status))throw new IllegalArgumentException("Invalid terminal outcome");
        releasePrepared(c,new Actor(l.userId(),l.spaceId()),l.taskId());
        if(daily(c,l.taskId())){
            for(var call:c.accounting().selectModelCall50(l.spaceId(),l.taskId()))
                if("STARTED".equals(call.status()))c.quota().unknown(call.id());
        }
        c.executionPolicy().stopActivity(l.taskId());
        // An open call cannot disappear when cancellation/provider failure interrupts its result.
        c.accounting().updateBudgetReservation133(l.taskId());
        c.accounting().updateModelCall134(reason, l.taskId());
        releaseRemaining(c,new Actor(l.userId(),l.spaceId()),l.taskId());
        c.conversation().updateTask135(status, reason, l.taskId());
        new PlanPersistence(c).closeInteractions(new Actor(l.userId(),l.spaceId()),l.taskId(),"CANCELLED");
        c.plan().finishResume(l.attemptId());
        String text;long messageVersion;
        { var rows29 = c.conversation().selectMessage29(l.assistantId()); var row29 = rows29.isEmpty() ? null : rows29.getFirst(); text=String.valueOf(blocks(row29.value1()).getFirst().get("text"));messageVersion=row29.value2()+1; }
        String messageStatus="SUCCEEDED".equals(status)?"COMPLETED":text.isEmpty()?"FAILED":"PARTIAL";
        c.conversation().updateMessage136(messageStatus, messageVersion, l.assistantId());
        c.dispatch().updateAttempt137("BLOCKED".equals(status)?"INTERRUPTED":status, reason, l.attemptId());c.dispatch().updateExecutionDispatch138(l.taskId());
        c.dispatch().deleteConversationLease139(l.attemptId());
        c.dispatch().updateExecutionSlot140();c.recovery().finishRequest(l.attemptId(),"SUCCEEDED".equals(status));
        Actor a=new Actor(l.userId(),l.spaceId());event(c,a,l.conversationId(),l.taskId(),"message.completed",messageVersion,Map.of("messageId",l.assistantId().toString(),"status",messageStatus));Task task=task(c,a,l.taskId(),false);
        var payload=new LinkedHashMap<String,Object>();payload.put("taskId",l.taskId().toString());payload.put("status",status);payload.put("reasonCode",reason);event(c,a,l.conversationId(),l.taskId(),"task.state_changed",task.version(),payload);return null;
    });}
}
