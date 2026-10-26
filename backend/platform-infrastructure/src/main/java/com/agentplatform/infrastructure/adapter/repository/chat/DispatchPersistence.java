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

/** Dispatch persistence use cases over typed MyBatis collaborators. */
final class DispatchPersistence extends ChatPersistenceSupport {
    DispatchPersistence(ChatMappers mappers) { super(mappers); }

    public List<Publish> publications(UUID relay){return tx(c->{
        List<Publish> result=new ArrayList<>();
        { var rows49 = c.dispatch().selectEventOutbox49();
            for (var row49 : rows49){UUID id=row49.id();c.dispatch().updateEventOutbox92(relay, id);result.add(new Publish(id,new Envelope(1,row49.eventId(),row49.spaceId(),row49.aggregateId(),row49.generation(),"execution.ready",row49.createdAt(),null,row49.aggregateId())));}
         }return List.copyOf(result);
    });}
    public void published(UUID relay,Publish item,boolean success){tx(c->{
        if(success)c.dispatch().updateEventOutbox93(item.id(), relay);
        else c.dispatch().updateEventOutbox94(item.id(), relay);return null;
    });}
    public void wakeDeferred(){tx(c->{
        new PlanPersistence(c).expireOne();
        c.dispatch().updateExecutionSlot95();
        { var rows48 = c.dispatch().selectExecutionDispatch48();
            for (var row48 : rows48){UUID id=row48.value1();long generation=row48.value2()+1;c.dispatch().updateExecutionDispatch96(generation, id);outbox(c,new Actor(row48.value4(),row48.value3()),id,generation);}
         }return null;
    });}
    public Lease claim(Envelope e,UUID owner){return tx(c->{
        if(e.schemaVersion()!=1||!"execution.ready".equals(e.kind())||!e.aggregateId().equals(e.payloadRef())||e.generation()<1)throw new ChatFailure(400,"INVALID_DISPATCH_MESSAGE");
        { var rows47 = c.dispatch().selectConsumerInbox47(e.eventId()); if(!rows47.isEmpty())return null; }
        var quotaTask=c.recovery().task(e.aggregateId());
        if(quotaTask!=null)new MyBatisQuotaLedger(c.quota()).lock(quotaTask.userId());
        boolean busy;long fence;
        { var rows46 = c.dispatch().selectExecutionSlot46(); var row46 = rows46.isEmpty() ? null : rows46.getFirst(); busy=row46.value1()!=null;fence=row46.value2()+1; }
        UUID actor,convo,model,credential,assistant;long revision,messageVersion;
        { var rows45 = c.conversation().selectTask45(e.aggregateId(), e.spaceId()); var row45 = rows45.isEmpty() ? null : rows45.getFirst(); if(row45 == null)throw new ChatFailure(400,"UNKNOWN_DISPATCH_TASK");convo=row45.value1(); }
        conversation(c,new Actor(null,e.spaceId()),convo,true);
        { var rows44 = c.conversation().selectTask44(e.spaceId(), e.aggregateId()); var row44 = rows44.isEmpty() ? null : rows44.getFirst();
            if(row44 == null)throw new ChatFailure(400,"UNKNOWN_DISPATCH_TASK");
            actor=row44.creatorId();convo=row44.conversationId();model=row44.modelId();credential=row44.credentialId();revision=row44.credentialRevision();
            if(e.generation()!=row44.generation()||!"READY".equals(row44.dispatchStatus())||!"QUEUED".equals(row44.status())){inbox(c,e,"IGNORED");return null;}
         }
        Actor a=new Actor(actor,e.spaceId());
        try { auth(c,a); } catch(ChatFailure denied) {
            if(denied.status()!=404)throw denied;
            conversation(c,a,convo,true);
            c.conversation().updateTask97(e.aggregateId());
            c.conversation().updateMessage98(e.aggregateId());
            c.dispatch().updateExecutionDispatch99(e.aggregateId());
            releaseRemaining(c,a,e.aggregateId());inbox(c,e,"IGNORED");
            assistantTerminalEvent(c,a,convo,e.aggregateId());
            event(c,a,convo,e.aggregateId(),"task.state_changed",task(c,a,e.aggregateId(),false).version(),Map.of("taskId",e.aggregateId().toString(),"status","FAILED","reasonCode","AUTHORIZATION_REVOKED"));return null;
        }
        conversation(c,a,convo,true);
        { var rows43 = c.conversation().selectTask43(e.aggregateId()); var row43 = rows43.isEmpty() ? null : rows43.getFirst();
            if(row43 == null||!ChatRuntime.bundle().hash().equals(row43.value1())){
                c.conversation().updateTask100(e.aggregateId());
                c.conversation().updateMessage101(e.aggregateId());
                c.dispatch().updateExecutionDispatch102(e.aggregateId());releaseRemaining(c,a,e.aggregateId());inbox(c,e,"IGNORED");
                assistantTerminalEvent(c,a,convo,e.aggregateId());event(c,a,convo,e.aggregateId(),"task.state_changed",task(c,a,e.aggregateId(),false).version(),Map.of("taskId",e.aggregateId().toString(),"status","BLOCKED","reasonCode","BUNDLE_INCOMPATIBLE"));return null;
            }
         }
        { var rows42 = c.conversation().selectTask42(convo, e.aggregateId(), e.aggregateId()); busy |= !rows42.isEmpty(); }
        if(!RecoveryPersistence.admitClaim(c,a,e.aggregateId())){inbox(c,e,"IGNORED");return null;}
        if(busy){c.dispatch().updateExecutionDispatch103(e.aggregateId());inbox(c,e,"DEFERRED");return null;}
        UUID attempt=Uuid7.next();
        c.dispatch().updateExecutionSlot104(e.aggregateId(), owner, fence);
        c.dispatch().updateExecutionDispatch105(owner, fence, e.aggregateId());
        c.dispatch().insertAttempt106(attempt, e.aggregateId(), owner, fence, e.generation(), e.aggregateId());
        c.dispatch().insertConversationLease107(e.spaceId(), convo, e.aggregateId(), attempt, owner, e.generation(), fence);
        c.conversation().updateTask108(e.aggregateId());inbox(c,e,"CLAIMED");
        { var rows41 = c.conversation().selectMessage41(e.aggregateId()); var row41 = rows41.isEmpty() ? null : rows41.getFirst(); assistant=row41.value1();messageVersion=row41.value2(); }
        Lease lease=new Lease(e.aggregateId(),e.spaceId(),actor,convo,attempt,owner,fence,model,credential,revision,assistant);
        RecoveryPersistence.claimed(c,lease);
        event(c,a,convo,e.aggregateId(),"task.state_changed",task(c,a,e.aggregateId(),false).version(),Map.of("taskId",e.aggregateId().toString(),"status","RUNNING"));
        event(c,a,convo,e.aggregateId(),"message.started",messageVersion,Map.of("messageId",assistant.toString(),"blockId",assistant.toString(),"role","ASSISTANT"));
        return lease;
    });}
    public void quarantine(String reason){tx(c->{c.dispatch().insertDispatchQuarantine110(Uuid7.next(), reason);return null;});}
    public void recoverStopped(ExecutionLedger.StoppedOwner proof){tx(c->{reconcileStopped(c,proof);return null;});}
}
