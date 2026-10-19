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

/** Shared authorization, ownership guards and canonical conversions, without SQL plumbing. */
abstract class ChatPersistenceSupport {

    public static final UUID BUNDLE=UUID.fromString("0199e000-0000-7000-8000-000000000004");
    static final JsonMapper JSON=JsonMapper.builder().build();
    protected final ChatMappers mappers;
    protected ChatPersistenceSupport(ChatMappers mappers) { this.mappers = mappers; }
    @FunctionalInterface protected interface Work<T> { T run(ChatMappers c); }
    protected <T> T tx(Work<T> work) { return tx(false, work); }
    protected <T> T tx(boolean snapshot, Work<T> work) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Application transaction boundary required");
        return work.run(mappers);
    }
    protected static void guardCommit(ChatMappers c, Lease lease, Instant deadline, boolean requireAuthorization) {
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) {
                    verifyCommitLease(c, lease, deadline);
                    if (requireAuthorization) {
                        auth(c, new Actor(lease.userId(), lease.spaceId()));
                        authorizeCredential(c, lease);
                        activeModel(c, lease);
                    }
                }
            });
    }
    protected static void verifyCommitLease(ChatMappers c,Lease l,Instant deadline){
        // Row locks prevent takeovers during the transaction; DB time still advances.
        // A slow SQL statement must not commit content after expiry. Terminal cleanup
        // clears the slot, so it is checked against the deadline captured before clearing.
        { var rows74 = c.dispatch().selectExecutionSlot74(); var row74 = rows74.isEmpty() ? null : rows74.getFirst(); 
            if(row74 == null)throw new ChatFailure(409,"EXECUTION_FENCE_LOST");Instant now=row74.value5();
            if(row74.value1()==null){if(!deadline.isAfter(now))throw new ChatFailure(409,"EXECUTION_FENCE_LOST");}
            else if(!l.taskId().equals(row74.value1())||!l.ownerId().equals(row74.value2())||l.fence()!=row74.value3()||row74.value4()==null||!row74.value4().isAfter(now))throw new ChatFailure(409,"EXECUTION_FENCE_LOST");
         }
    }
    static String json(Object v){return JSON.writeValueAsString(v);}
    @SuppressWarnings("unchecked") protected static List<Map<String,Object>> blocks(String value){return JSON.readValue(value,List.class);}
    @SuppressWarnings("unchecked") static Map<String,Object> object(String value){return JSON.readValue(value,Map.class);}
    static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    protected static void auth(ChatMappers c,Actor a){
        { var rows73 = c.conversation().selectAuthorizeChatActor73(a.userId(), a.spaceId()); var row73 = rows73.isEmpty() ? null : rows73.getFirst(); 
            if(row73 == null||!row73.value1())throw new ChatFailure(404,"RESOURCE_NOT_FOUND");
         }
    }
    protected static void conversation(ChatMappers c,Actor a,UUID id,boolean lock){
        { var rows72 = c.conversation().selectConversation72(a.spaceId(), id, lock); if(rows72.isEmpty())throw new ChatFailure(404,"RESOURCE_NOT_FOUND"); }
    }
    protected static Task readTask(TaskProjection r){return new Task(r.id(),r.conversationId(),r.status(),r.cancelRequested(),r.reasonCode(),r.version());}
    protected static Conversation readConversation(ConversationProjection r){return new Conversation(r.id(),r.title(),r.version(),r.updatedAt());}
    protected static Task task(ChatMappers c,Actor a,UUID id,boolean lock){
        { var rows71 = c.conversation().selectTask71(a.spaceId(), id, lock); var row71 = rows71.isEmpty() ? null : rows71.getFirst(); if(row71 == null)throw new ChatFailure(404,"RESOURCE_NOT_FOUND");return readTask(row71); }
    }
    protected static UUID idempotency(ChatMappers c,Actor a,UUID key,String path,String fingerprint){
        { c.conversation().selectGuard70((long)(a+path+key).hashCode()); }
        { var rows69 = c.conversation().selectIdempotencyRecord69(a.userId(), a.spaceId(), path, key); var row69 = rows69.isEmpty() ? null : rows69.getFirst(); 
            if(row69 == null)return null;if(!fingerprint.equals(row69.value1()))throw new ChatFailure(409,"IDEMPOTENCY_CONFLICT");return row69.value2();
         }
    }
    protected static void remember(ChatMappers c,Actor a,UUID key,String path,String fingerprint,UUID resource){
        c.conversation().insertIdempotencyRecord75(a.userId(), a.spaceId(), path, key, fingerprint, resource);
    }
    protected static long event(ChatMappers c,Actor a,UUID conversation,UUID task,String kind,long version,Map<String,Object> payload){
        long seq;
        { var rows68 = c.conversation().updateConversation68(a.spaceId(), conversation); var row68 = rows68.isEmpty() ? null : rows68.getFirst(); if(row68 == null)throw new ChatFailure(404,"RESOURCE_NOT_FOUND");seq=row68.value1(); }
        c.conversation().insertPlatformEvent76(Uuid7.next(), a.spaceId(), conversation, task, seq, kind, version, json(payload));return seq;
    }
    protected static List<Map<String,Object>> textBlocks(UUID id,String text){return List.of(Map.of("type","TEXT","blockId",id.toString(),"text",text));}
    protected static void outbox(ChatMappers c,Actor a,UUID task,long generation){
        c.dispatch().insertEventOutbox77(Uuid7.next(), a.spaceId(), Uuid7.next(), task, generation);
    }
    protected static Task submit(ChatMappers c,Actor a,UUID convo,UUID key,UUID selectedModel,String selectedSource,String text){
        auth(c,a);if(text==null||text.isBlank()||text.codePointCount(0,text.length())>8192)throw new ChatFailure(400,"INVALID_CHAT_TEXT");
        String path="/conversations/"+convo+"/tasks",fingerprint=hash(selectedSource==null?selectedModel+"\n"+text:selectedModel+"\n"+selectedSource+"\n"+text);UUID existing=idempotency(c,a,key,path,fingerprint);
        if(existing!=null)return task(c,a,existing,false);
        { c.conversation().selectGuard59(); }
        c.dispatch().updateExecutionSlot79();
        conversation(c,a,convo,true);
        { var rows58 = c.conversation().selectTask58(convo); var row58 = rows58.isEmpty() ? null : rows58.getFirst(); if(row58.value1()>=100)throw new ChatFailure(409,"CONVERSATION_TASK_LIMIT"); }
        { var rows57 = c.conversation().selectTask57(a.userId()); var row57 = rows57.isEmpty() ? null : rows57.getFirst(); if(row57.value1()>=20)throw new ChatFailure(429,"QUEUE_LIMIT_EXCEEDED"); }
        { var rows56 = c.dispatch().selectEventOutbox56(); var row56 = rows56.isEmpty() ? null : rows56.getFirst(); if(row56.value1()>=10000||(row56.value2()!=null&&row56.value2().isBefore(Instant.now().minusSeconds(900))))throw new ChatFailure(503,"DISPATCH_UNAVAILABLE"); }
        var resolved=resolve(c,a,selectedModel,selectedSource);
        UUID model=(UUID)resolved.get("modelId");String policy=(String)resolved.get("credentialSource");
        UUID credential;long revision,modelVersion,inputPrice,outputPrice;String name,pricing;
        { var rows55 = c.configuration().selectUserModelCredential55(a.spaceId(), a.userId(), model, "USER".equals(policy)); var row55 = rows55.isEmpty() ? null : rows55.getFirst(); 
            if(row55 == null)throw new ChatFailure(422,"CREDENTIAL_SOURCE_UNAVAILABLE");credential=row55.id();revision=row55.revision();name=row55.modelName();pricing=row55.pricingVersion();modelVersion=row55.modelVersion();inputPrice=row55.inputMicrosPerMillion();outputPrice=row55.outputMicrosPerMillion();
         }
        c.accounting().insertBudgetAccount80(a.spaceId(), a.userId());
        long perCall=(8192L*inputPrice+1024L*outputPrice+999999)/1000000,totalCost=perCall*4;
        if(c.accounting().updateBudgetAccount81(totalCost, a.spaceId(), a.userId(), totalCost)!=1)throw new ChatFailure(429,"QUOTA_EXCEEDED");
        var bundle=ChatRuntime.bundle();
        c.configuration().insertRuntimeBundle82(BUNDLE, bundle.hash(), json(bundle));
        { var rows54 = c.configuration().selectRuntimeBundle54(BUNDLE); var row54 = rows54.isEmpty() ? null : rows54.getFirst(); if(row54 == null||!bundle.hash().equals(row54.value1()))throw new ChatFailure(409,"BUNDLE_INCOMPATIBLE"); }
        UUID snapshot=Uuid7.next(),id=Uuid7.next(),userMessage=Uuid7.next(),assistant=Uuid7.next();
        var values=new TreeMap<String,Object>();values.putAll(resolved);values.put("modelId",model.toString());values.put("modelName",name);values.put("modelVersion",modelVersion);values.put("credentialId",credential.toString());values.put("credentialRevision",revision);values.put("window",8192);values.put("maxOutput",1024);values.put("thinking",false);values.put("pricingVersion",pricing);values.put("inputPrice",inputPrice);values.put("outputPrice",outputPrice);values.put("perCallMicros",perCall);values.put("bundleHash",bundle.hash());String config=json(values);

        c.configuration().insertChatConfigSnapshot83(snapshot, a.spaceId(), model, credential, revision, config, hash(config));
        c.conversation().insertTask84(id, a.spaceId(), convo, a.userId(), snapshot, BUNDLE, totalCost, com.agentplatform.types.RequestTrace.current());
        c.conversation().insertMessage85(userMessage, a.spaceId(), convo, id, json(textBlocks(userMessage,text)), assistant, a.spaceId(), convo, id, json(textBlocks(assistant,"")));
        c.dispatch().insertExecutionDispatch86(id);outbox(c,a,id,1);
        event(c,a,convo,id,"task.accepted",1,Map.of("taskId",id.toString(),"status","QUEUED"));remember(c,a,key,path,fingerprint,id);audit(c,a,"TASK_SUBMITTED",id,com.agentplatform.types.RequestTrace.current());return task(c,a,id,false);
    }
    protected static void inbox(ChatMappers c,Envelope e,String disposition){c.dispatch().insertConsumerInbox109(e.eventId(), e.spaceId(), e.aggregateId(), e.generation(), disposition);}
    static void owned(ChatMappers c,Lease l,boolean cancellationAllowed){
        { var rows40 = c.dispatch().selectExecutionSlot40(l.taskId(), l.ownerId(), l.fence()); var row40 = rows40.isEmpty() ? null : rows40.getFirst(); if(row40 == null)throw new ChatFailure(409,"EXECUTION_FENCE_LOST");guardCommit(c,l,row40.value1(),!cancellationAllowed); }
        Actor a=new Actor(l.userId(),l.spaceId());conversation(c,a,l.conversationId(),true);
        Task task=task(c,a,l.taskId(),true);
        { var rows39 = c.dispatch().selectExecutionDispatch39(l.taskId(), l.ownerId(), l.fence(), l.attemptId(), l.spaceId(), l.ownerId(), l.fence(), l.spaceId(), l.conversationId(), l.ownerId(), l.fence()); if(rows39.isEmpty())throw new ChatFailure(409,"EXECUTION_FENCE_LOST"); }
        { var rows38 = c.conversation().selectTask38(l.taskId(), l.userId(), l.conversationId(), l.modelId(), l.credentialId(), l.credentialRevision(), l.assistantId()); if(rows38.isEmpty())throw new ChatFailure(409,"EXECUTION_FENCE_LOST"); }
        if(!"RUNNING".equals(task.status()))throw new ChatFailure(409,"EXECUTION_FENCE_LOST");
        if(!cancellationAllowed){auth(c,a);if(task.cancelRequested())throw new ChatFailure(409,"TASK_CANCEL_REQUESTED");}
    }
    static void settleCall(ChatMappers c,Lease l,Call call,HarnessData.Round round,String requestId,String error,boolean real){
        owned(c,l,true);if(round!=null)MyBatisExecutionLedger.guard(c,l);long allowance=callAllowance(c,l);var prices=frozen(c,l);String state;
        { var rows34 = c.accounting().selectBudgetReservation34(call.id(), l.taskId(), l.attemptId(), l.spaceId()); var row34 = rows34.isEmpty() ? null : rows34.getFirst(); if(row34 == null)throw new ChatFailure(409,"CALL_NOT_FOUND");state=row34.value1(); }
        if(!"RESERVED".equals(state))return;
        if(round==null){
            boolean unbilled=Set.of("PROVIDER_RATE_LIMITED","PROVIDER_AUTH_REJECTED","PROVIDER_BALANCE_EXHAUSTED","CALL_NOT_SENT").contains(error==null?"":error);
            String outcome=unbilled||"PROVIDER_SERVER_REJECTED".equals(error)?"FAILED":"PROVIDER_USAGE_MISSING".equals(error)?"COMPLETED":"UNKNOWN";
            c.accounting().updateModelCall120(outcome, error, call.id());
            c.accounting().updateBudgetReservation121(unbilled?"RELEASED":"RECONCILING", call.id());
            if(unbilled){c.accounting().updateBudgetAccount122(allowance, l.spaceId(), l.userId());if(real)c.accounting().updateAcceptanceModelMeter123(allowance);}return;
        }
        if(round.inputTokens()<0||round.inputTokens()>8192||round.outputTokens()<0||round.outputTokens()>1024)throw new ChatFailure(409,"PROVIDER_USAGE_EXCEEDS_RESERVATION");
        long tokens=round.inputTokens()+round.outputTokens(),cost=(round.inputTokens()*((Number)prices.getOrDefault("inputPrice",2000000L)).longValue()+round.outputTokens()*((Number)prices.getOrDefault("outputPrice",8000000L)).longValue()+999999)/1000000;
        if(tokens>9216||cost>allowance)throw new ChatFailure(409,"PROVIDER_USAGE_EXCEEDS_RESERVATION");
        c.accounting().updateModelCall124(round.inputTokens(), round.outputTokens(), cost, requestId, call.id());
        c.accounting().updateBudgetReservation125(call.id());
        c.accounting().updateBudgetAccount126(allowance, tokens, cost, l.spaceId(), l.userId());
        if(real)c.accounting().updateAcceptanceModelMeter127(allowance, cost);
        event(c,new Actor(l.userId(),l.spaceId()),l.conversationId(),l.taskId(),"usage.updated",call.ordinal(),Map.of("callId",call.id().toString(),"inputTokens",round.inputTokens(),"outputTokens",round.outputTokens(),"costMicros",cost,"estimated",true));return;
    }
    static void authorizeCredential(ChatMappers c,Lease l){
        { var rows31 = c.conversation().selectAuthorizeChatCredential31(l.credentialId(), l.credentialRevision(), l.spaceId(), l.userId()); var row31 = rows31.isEmpty() ? null : rows31.getFirst(); 
            if(row31 == null||!row31.value1())throw new ChatFailure(403,"CREDENTIAL_REVOKED");
         }
    }
    protected static void releaseRemaining(ChatMappers c,Actor a,UUID task){
        c.accounting().updateBudgetAccount129(task, a.spaceId(), a.userId());
        c.conversation().updateTask130(task);
    }
    protected static void assistantTerminalEvent(ChatMappers c,Actor a,UUID conversation,UUID task){
        { var rows30 = c.conversation().selectMessage30(a.spaceId(), task); var row30 = rows30.isEmpty() ? null : rows30.getFirst(); 
            if(row30 != null)event(c,a,conversation,task,"message.completed",row30.value3(),Map.of("messageId",row30.value1().toString(),"status",row30.value2()));
         }
    }
    protected static void releasePrepared(ChatMappers c,Actor a,UUID task){
        c.accounting().releasePreparedReservations(task, a.spaceId(), a.userId());
        c.accounting().updateModelCall132(task);
    }
    /** Legacy boolean-only reconciliation is deliberately unusable. */
    public void recoverStopped(UUID owner){throw new ChatFailure(409,"STOPPED_OWNER_PROOF_REQUIRED");}
    protected static void reconcileStopped(ChatMappers c,ExecutionLedger.StoppedOwner proof){
        if(proof==null||proof.ownerId()==null||proof.taskId()==null||proof.attemptId()==null||proof.fence()<1
                ||proof.containerId()==null||!proof.containerId().matches("[a-f0-9]{64}")
                ||proof.imageId()==null||!proof.imageId().matches("sha256:[a-f0-9]{64}")
                ||proof.evidenceHash()==null||!proof.evidenceHash().matches("[a-f0-9]{64}")
                ||proof.startedAt()==null||proof.stoppedAt()==null||proof.observedAt()==null
                ||!proof.startedAt().isBefore(proof.stoppedAt())||proof.stoppedAt().isAfter(proof.observedAt()))
            throw new ChatFailure(409,"STOPPED_OWNER_PROOF_INVALID");
        Instant databaseNow;UUID slotTask,slotOwner;long fence;
        { var rows28 = c.dispatch().selectExecutionSlot28(); var row28 = rows28.isEmpty() ? null : rows28.getFirst(); 
            slotTask=row28.value1();slotOwner=row28.value2();fence=row28.value3();databaseNow=row28.value5();
            if(proof.observedAt().isAfter(databaseNow.plusSeconds(5))||proof.observedAt().isBefore(databaseNow.minusSeconds(300)))throw new ChatFailure(409,"STOPPED_OWNER_PROOF_STALE");
            if(!proof.ownerId().equals(slotOwner)||!proof.taskId().equals(slotTask)||proof.fence()!=fence){
                { var rows27 = c.conversation().selectStoppedOwnerProof27(proof.ownerId()); var row27 = rows27.isEmpty() ? null : rows27.getFirst(); 
                    if(row27 != null&&proof.evidenceHash().equals(row27.value1()))return;
                 }throw new ChatFailure(409,"STOPPED_OWNER_PROOF_MISMATCH");
            }
            if(row28.value4()==null||row28.value4().isAfter(databaseNow))throw new ChatFailure(409,"OWNER_LEASE_NOT_EXPIRED");
         }
        UUID space,user,conversation,assistant;Instant attemptStarted;
        { var rows26 = c.conversation().selectTask26(slotTask, proof.attemptId(), slotOwner, fence); var row26 = rows26.isEmpty() ? null : rows26.getFirst(); 
            if(row26 == null)throw new ChatFailure(409,"STOPPED_OWNER_PROOF_MISMATCH");space=row26.value1();user=row26.value2();conversation=row26.value3();assistant=row26.value4();attemptStarted=row26.value5();
         }
        if(proof.startedAt().isAfter(attemptStarted)||proof.stoppedAt().isBefore(attemptStarted))throw new ChatFailure(409,"STOPPED_OWNER_PROOF_MISMATCH");
        Actor actor=new Actor(user,space);conversation(c,actor,conversation,true);task(c,actor,slotTask,true);
        c.dispatch().updateExecutionDispatch141(slotTask);
        c.dispatch().updateAttempt142(proof.attemptId());
        c.conversation().insertStoppedOwnerProof143(slotOwner, space, slotTask, proof.attemptId(), fence, proof.containerId(), proof.imageId(), proof.startedAt(), proof.stoppedAt(), proof.observedAt(), proof.evidenceHash());
        boolean authorized=true;
        try{auth(c,actor);{ var rows25 = c.conversation().selectAuthorizeChatCredential25(slotTask); var row25 = rows25.isEmpty() ? null : rows25.getFirst(); 
            authorized=row25.value1()&&"ACTIVE".equals(row25.value2()); }
        }catch(ChatFailure revoked){authorized=false;}
        boolean uncertain,started;String finalText=null;
        { var rows24 = c.accounting().selectModelCall24(slotTask, slotTask); var row24 = rows24.isEmpty() ? null : rows24.getFirst(); uncertain=row24.value1();started=row24.value2(); }
        { var rows23 = c.ledger().selectModelTurn23(slotTask); var row23 = rows23.isEmpty() ? null : rows23.getFirst(); 
            if(row23 != null){var round=object(row23.value1());if("stop".equals(round.get("finishReason"))&&round.get("calls") instanceof List<?> calls&&calls.isEmpty())finalText=(String)round.get("text");}
         }
        String status,reason;
        if(authorized&&!started&&!uncertain&&!task(c,actor,slotTask,false).cancelRequested()){
            // Prepared reservations move back into the task's existing admission hold, not a new budget.
            c.accounting().restorePreparedReservationAllowance(slotTask, slotTask);
            c.accounting().updateModelCall145(slotTask);
            status="QUEUED";reason="OWNER_STOPPED_BEFORE_CALL";
            c.dispatch().updateExecutionDispatch146(slotTask);
            { var rows22 = c.dispatch().selectExecutionDispatch22(slotTask); var row22 = rows22.isEmpty() ? null : rows22.getFirst(); outbox(c,actor,slotTask,row22.value1()); }
        }else{
            status=authorized&&!uncertain&&finalText!=null&&!task(c,actor,slotTask,false).cancelRequested()?"SUCCEEDED":"BLOCKED";
            reason="SUCCEEDED".equals(status)?null:authorized?"WORKER_INTERRUPTED":"AUTHORIZATION_REVOKED";
            releasePrepared(c,actor,slotTask);
            c.accounting().updateModelCall147(slotTask);
            c.accounting().updateBudgetReservation148(slotTask);
            c.ledger().updateToolOperation149(slotTask);
            releaseRemaining(c,actor,slotTask);
            if("SUCCEEDED".equals(status))c.conversation().updateMessage150(json(textBlocks(assistant,finalText)), assistant);
            else c.conversation().updateMessage151(assistant);
            c.dispatch().updateExecutionDispatch152(slotTask);
            assistantTerminalEvent(c,actor,conversation,slotTask);
        }
        c.dispatch().updateAttempt153(proof.attemptId());
        c.conversation().updateTask154(status, reason, slotTask);
        c.dispatch().deleteConversationLease155(proof.attemptId());
        c.dispatch().updateExecutionSlot156();
        var payload=new LinkedHashMap<String,Object>();payload.put("taskId",slotTask.toString());payload.put("status",status);payload.put("reasonCode",reason);
        event(c,actor,conversation,slotTask,"task.state_changed",task(c,actor,slotTask,false).version(),payload);
    }
    protected static void audit(ChatMappers c,Actor a,String kind,UUID resource,UUID trace){
        c.conversation().insertChatAudit163(Uuid7.next(), a.spaceId(), a.userId(), kind, resource, trace);
    }
    protected static Map<String,Object> preferences(ChatMappers c,Actor a){
        long version=0;{ var rows15 = c.configuration().selectUserPreferenceRevision15(a.userId()); var row15 = rows15.isEmpty() ? null : rows15.getFirst(); if(row15 != null)version=row15.value1(); }
        Map<String,Object> result=new LinkedHashMap<>();result.put("version",version);result.put("defaultModelId",null);result.put("defaultCredentialSource","PLATFORM");
        { var rows14 = c.configuration().selectUserPreference14(a.userId()); for (var row14 : rows14)result.put(row14.value1(),JSON.readValue(row14.value2(),Object.class)); }return result;
    }
    protected static void validateModel(ChatMappers c,UUID model){
        { var rows13 = c.configuration().selectModelDefinition13(model); if(rows13.isEmpty())throw new ChatFailure(422,"MODEL_CAPABILITY_MISMATCH"); }
    }
    protected static Map<String,Object> resolve(ChatMappers c,Actor a,UUID selected,String source){
        var prefs=preferences(c,a);UUID model=selected;
        if(model==null&&prefs.get("defaultModelId")!=null)model=UUID.fromString((String)prefs.get("defaultModelId"));
        if(model==null){ var rows12 = c.configuration().selectModelDefinition12(); var row12 = rows12.isEmpty() ? null : rows12.getFirst(); if(row12 == null)throw new ChatFailure(503,"MODEL_UNAVAILABLE");model=row12.value1(); }
        validateModel(c,model);String policy=source==null?(String)prefs.get("defaultCredentialSource"):source;
        if(!Set.of("PLATFORM","USER").contains(policy))throw new ChatFailure(422,"INVALID_CREDENTIAL_SOURCE");
        return Map.of("modelId",model,"credentialSource",policy);
    }
    protected static boolean preferenceDefined(ChatMappers c,Actor a,String key){{ var rows11 = c.configuration().selectUserPreference11(a.userId(), key); return !rows11.isEmpty(); }}
    protected static Map<String,Object> credentialMetadata(ChatMappers c,Actor a){
        long version=0;boolean active=false;
        { var rows9 = c.configuration().selectUserModelCredential9(a.spaceId(), a.userId()); var row9 = rows9.isEmpty() ? null : rows9.getFirst(); if(row9 != null){version=row9.value1();active="ACTIVE".equals(row9.value2());} }
        boolean platform;{ var rows8 = c.configuration().selectModelBinding8(a.spaceId(), a.userId()); platform = !rows8.isEmpty(); }
        return Map.of("version",version,"userConfigured",active,"platformAvailable",platform);
    }
    protected static Map<String,Object> frozen(ChatMappers c,Lease l){
        { var rows6 = c.conversation().selectTask6(l.taskId(), l.spaceId()); var row6 = rows6.isEmpty() ? null : rows6.getFirst(); if(row6 == null)throw new ChatFailure(404,"RESOURCE_NOT_FOUND");return object(row6.value1()); }
    }
    protected static long callAllowance(ChatMappers c,Lease l){return ((Number)frozen(c,l).getOrDefault("perCallMicros",24576L)).longValue();}
    static void activeModel(ChatMappers c,Lease l){validateModel(c,l.modelId());}
    public boolean ready(){try{return tx(c->{{ var rows1 = c.ledger().selectCheckpoint1(); var row1 = rows1.isEmpty() ? null : rows1.getFirst(); return row1 != null&&row1.value1(); }});}catch(ChatFailure e){return false;}}
}
