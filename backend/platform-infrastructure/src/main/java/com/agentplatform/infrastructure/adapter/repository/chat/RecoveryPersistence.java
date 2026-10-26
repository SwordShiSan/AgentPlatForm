package com.agentplatform.infrastructure.adapter.repository.chat;

import com.agentplatform.domain.chat.*;
import com.agentplatform.domain.chat.ChatData.*;
import com.agentplatform.domain.chat.RecoveryLedger.*;
import com.agentplatform.infrastructure.dao.chat.ChatMappers;
import com.agentplatform.infrastructure.dao.po.RecoveryRows.*;
import com.agentplatform.types.Uuid7;
import com.agentplatform.types.harness.HarnessData.*;
import com.agentplatform.types.harness.HarnessData.Message;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Durable qualification and atomic snapshots. Every entry uses the application transaction. */
final class RecoveryPersistence extends ChatPersistenceSupport {
    RecoveryPersistence(ChatMappers mappers) { super(mappers); }

    public Startup executionStartup(Lease l) { return tx(c -> {
        MyBatisExecutionLedger.guard(c,l);
        if(daily(c,l.taskId())){
            if(c.executionPolicy().beginActivity(l.taskId())!=1)throw new ChatFailure(504,"PROVIDER_TIMEOUT");
        }else c.recovery().initializeDeadline(l.taskId());
        var task=c.recovery().task(l.taskId());var request=c.recovery().attemptRequest(l.attemptId());
        boolean restoring=request!=null;
        if(restoring)verify(c,l,request.checkpointId(),ChatRuntime.bundle());
        long next=c.recovery().turns(l.taskId()).stream().filter(t->"COMMITTED".equals(t.status())).mapToLong(TurnRow::ordinal).max().orElse(0)+1;
        return new Startup(restoring,restoring?request.checkpointId():null,next,task.deadline(),history(c,l));
    }); }

    static List<Message> input(ChatMappers c,Lease l) {
        var cp=c.recovery().latest(l.taskId());
        if(cp!=null)return decodeMessages(object(cp.canonicalJson()).get("input"));
        var turns=c.recovery().turns(l.taskId());
        if(!turns.isEmpty())return List.of(JSON.readValue(turns.getFirst().inputJson(),Message[].class));
        return new ExecutionPersistence(c).history(l);
    }
    static List<Message> decodeMessages(Object value) {
        if(!(value instanceof List<?> values)||values.isEmpty())throw new ChatFailure(409,"CHECKPOINT_INPUT_MISSING");
        return List.of(JSON.readValue(json(value),Message[].class));
    }
    static List<Message> history(ChatMappers c,Lease l) {
        MyBatisExecutionLedger.guard(c,l);var values=new ArrayList<>(input(c,l));
        var operations=c.recovery().operations(l.taskId());
        var controlInputs=c.plan().consumedInputs(l.taskId());
        for(var turn:c.recovery().turns(l.taskId()))if("COMMITTED".equals(turn.status())) {
            Round round=JSON.readValue(turn.roundJson(),Round.class);
            values.add(new Message(Role.ASSISTANT,round.text(),round.calls(),List.of()));
            var observations=operations.stream().filter(o->o.turnId().equals(turn.id())&&"SUCCEEDED".equals(o.status()))
                    .map(o->{
                        var observation=JSON.readValue(o.resultJson(),Observation.class);
                        var input=controlInputs.stream().filter(i->i.callId().equals(observation.callId())).findFirst();
                        if(input.isEmpty())return observation;
                        String projection=json(Map.of("toolResult",observation.text(),"optionalControlInput",object(input.get().contentJson()),"answerId",input.get().answerId(),"answerHash",input.get().answerHash()));
                        return new Observation(observation.callId(),observation.name(),projection,observation.status());
                    }).toList();
            if(!observations.isEmpty())values.add(new Message(Role.TOOL,"",List.of(),observations));
        }
        return List.copyOf(values);
    }
    static Map<String,Object> canonical(ChatMappers c,Lease l) {
        var facts=new TreeMap<String,Object>(object(c.recovery().facts(l.taskId())));facts.put("input",input(c,l));facts.put("controlInputs",c.plan().consumedInputs(l.taskId()));return facts;
    }
    static String canonicalJson(Object value) { return MyBatisExecutionLedger.canonicalJson(value); }

    public UUID commitCheckpoint(Lease l,Bundle bundle,CheckpointDraft draft) { return tx(c->{
        MyBatisExecutionLedger.guard(c,l);MyBatisExecutionLedger.verifyBundle(c,l,bundle);
        var history=history(c,l);
        if(!canonicalJson(history).equals(canonicalJson(draft.history())))throw new ChatFailure(409,"CHECKPOINT_HISTORY_CHANGED");
        if(draft.phase()==CheckpointPhase.INPUT_READY&&!c.recovery().turns(l.taskId()).isEmpty())throw new ChatFailure(409,"CHECKPOINT_INPUT_TOO_LATE");
        var facts=canonical(c,l);String body=canonicalJson(facts),bodyHash=hash(body);
        long version=MyBatisExecutionLedger.stateCas(c,l,"checkpoint_agent_state",draft.expectedVersion(),draft.state());
        if(version<1)throw new ChatFailure(409,"STATE_CAS_CONFLICT");
        String encoded=Base64.getEncoder().encodeToString(draft.state());
        var state=Map.of("agent_state",Map.of("sourceKey","checkpoint_agent_state","version",version,"body",encoded,"hash",hash(new String(draft.state(),StandardCharsets.UTF_8))));
        String stateJson=canonicalJson(state),stateHash=hash(stateJson);
        long watermark=c.recovery().turns(l.taskId()).stream().filter(t->"COMMITTED".equals(t.status())).count();
        String manifest=canonicalJson(Map.of("version",2,"phase",draft.phase().name(),"bundle",bundle,"watermark",watermark,
                "canonicalHash",bodyHash,"stateHash",stateHash,"states",Map.of("agent_state",Map.of("version",version,"hash",hash(new String(draft.state(),StandardCharsets.UTF_8))))));
        UUID id=Uuid7.next();c.recovery().insertCheckpoint(l,id,BUNDLE,bundle.hash(),draft.phase().name(),manifest,hash(manifest),body,bodyHash,stateJson,stateHash,watermark);
        return id;
    }); }

    static CheckpointRow verifySnapshot(ChatMappers c,TaskRow task,UUID id,Bundle bundle) {
        var cp=c.recovery().checkpoint(id,task.id());if(cp==null)throw new ChatFailure(409,"CHECKPOINT_NOT_COMMITTED");
        try {
            var manifest=object(cp.manifestJson());
            if(!bundle.hash().equals(cp.bundleHash())||!canonicalJson(bundle).equals(canonicalJson(manifest.get("bundle"))))throw new ChatFailure(409,"BUNDLE_INCOMPATIBLE");
            for(var part:List.of(List.of(cp.manifestJson(),cp.manifestHash()),List.of(cp.canonicalJson(),cp.canonicalHash()),List.of(cp.stateJson(),cp.stateHash())))
                if(!hash(canonicalJson(object(part.getFirst()))).equals(part.getLast()))throw new ChatFailure(409,"CHECKPOINT_HASH_MISMATCH");
            if(!Objects.equals(manifest.get("canonicalHash"),cp.canonicalHash())||!Objects.equals(manifest.get("stateHash"),cp.stateHash())
                    ||((Number)manifest.get("version")).intValue()!=2||!cp.phase().equals(manifest.get("phase"))
                    ||((Number)manifest.get("watermark")).longValue()!=cp.watermark())throw new ChatFailure(409,"CHECKPOINT_HASH_MISMATCH");
            Map<String,Object> root=object(cp.canonicalJson());decodeMessages(root.get("input"));
            var current=object(c.recovery().facts(task.id()));
            for(String key:List.of("turns","calls","operations"))if(!canonicalJson(root.get(key)).equals(canonicalJson(current.get(key))))throw new ChatFailure(409,"CHECKPOINT_LEDGER_ADVANCED");
            if(root.containsKey("controlInputs")&&!canonicalJson(root.get("controlInputs")).equals(canonicalJson(c.plan().consumedInputs(task.id()))))throw new ChatFailure(409,"CHECKPOINT_CONTROL_INPUT_ADVANCED");
            long committed=c.recovery().turns(task.id()).stream().filter(t->"COMMITTED".equals(t.status())).count();
            if(cp.watermark()!=committed)throw new ChatFailure(409,"CHECKPOINT_LEDGER_ADVANCED");
            var states=object(cp.stateJson());if(!states.keySet().equals(Set.of("agent_state")))throw new ChatFailure(409,"CHECKPOINT_STATE_MISSING");
            var state=object(json(states.get("agent_state")));byte[] bytes=Base64.getDecoder().decode((String)state.get("body"));
            if(bytes.length==0||bytes.length>1048576||((Number)state.get("version")).longValue()<1||!hash(new String(bytes,StandardCharsets.UTF_8)).equals(state.get("hash")))throw new ChatFailure(409,"CHECKPOINT_HASH_MISMATCH");
            var expected=Map.of("agent_state",Map.of("version",state.get("version"),"hash",state.get("hash")));
            if(!canonicalJson(expected).equals(canonicalJson(manifest.get("states"))))throw new ChatFailure(409,"CHECKPOINT_HASH_MISMATCH");
            var sdk=object(new String(bytes,StandardCharsets.UTF_8));
            if(!task.userId().toString().equals(sdk.get("user_id"))||!task.conversationId().toString().equals(sdk.get("session_id")))
                throw new ChatFailure(409,"CHECKPOINT_STATE_SCOPE_MISMATCH");
            try {com.agentplatform.infrastructure.harness.agentscope.CheckpointStateValidator.validate(bytes,task.userId().toString(),task.conversationId().toString());}
            catch(RuntimeException invalid) {throw new ChatFailure(409,"CHECKPOINT_STATE_INVALID");}
            return cp;
        } catch(ChatFailure failure) {throw failure;} catch(RuntimeException invalid) {throw new ChatFailure(409,"CHECKPOINT_HASH_MISMATCH");}
    }
    static void verify(ChatMappers c,Lease l,UUID id,Bundle bundle) {
        MyBatisExecutionLedger.guard(c,l);MyBatisExecutionLedger.verifyBundle(c,l,bundle);verifySnapshot(c,c.recovery().task(l.taskId()),id,bundle);
    }
    static void copy(ChatMappers c,Lease l,UUID id) {
        verify(c,l,id,ChatRuntime.bundle());
        var cp=c.recovery().checkpoint(id,l.taskId());if(cp.attemptId().equals(l.attemptId())||!MyBatisExecutionLedger.stateKeys(c,l).isEmpty())throw new ChatFailure(409,"CHECKPOINT_TARGET_NOT_EMPTY");
        var state=object(json(object(cp.stateJson()).get("agent_state")));
        if(MyBatisExecutionLedger.stateCas(c,l,"agent_state",0,Base64.getDecoder().decode((String)state.get("body")))!=1)throw new ChatFailure(409,"STATE_CAS_CONFLICT");
        for(var turn:c.recovery().turns(l.taskId()))if("OPEN".equals(turn.status()))c.recovery().bindTurn(l,turn.id());
        for(var op:c.recovery().operations(l.taskId()))if("PREPARED".equals(op.status()))c.recovery().bindOperation(l,op.id());
    }

    private static TaskRow locked(ChatMappers c,UUID id) {
        var row=c.recovery().task(id);if(row==null)throw new ChatFailure(404,"RESOURCE_NOT_FOUND");
        new MyBatisQuotaLedger(c.quota()).lock(row.userId());
        c.dispatch().updateExecutionSlot87();
        Actor actor=new Actor(row.userId(),row.spaceId());conversation(c,actor,row.conversationId(),true);task(c,actor,id,true);
        return c.recovery().task(id);
    }
    private static void core(ChatMappers c,TaskRow t,CheckpointIdentity cp,boolean queued,boolean verifyPrivateState) {
        auth(c,new Actor(t.userId(),t.spaceId()));
        if(t.cancelRequested()||"CANCELLED".equals(t.status()))throw new ChatFailure(409,"RECOVERY_CANCELLED");
        if("SUCCEEDED".equals(t.status()))throw new ChatFailure(409,"RECOVERY_ALREADY_COMPLETE");
        if(c.recovery().live(t.id())>0)throw new ChatFailure(409,"EXECUTION_STOP_UNCONFIRMED");
        UUID latest=c.recovery().latestAttempt(t.id());
        if(latest==null||c.recovery().hasStop(t.id(),latest)!=1)throw new ChatFailure(409,"EXECUTION_STOP_NOT_CONFIRMED");
        if(!queued&&!Set.of("BLOCKED","FAILED").contains(t.status()))throw new ChatFailure(409,"RECOVERY_STATE_CONFLICT");
        if(c.recovery().unknown(t.id())>0)throw new ChatFailure(409,"RECOVERY_UNKNOWN_EFFECT");
        if(c.recovery().newerTasks(t.id())>0)throw new ChatFailure(409,"RECOVERY_CONVERSATION_ADVANCED");
        if(cp==null)throw new ChatFailure(409,"CHECKPOINT_NOT_COMMITTED");
        if(!latest.equals(cp.attemptId()))throw new ChatFailure(409,"CHECKPOINT_ATTEMPT_MISMATCH");
        if(daily(c,t.id())){
            if(c.executionPolicy().policy(t.id()).activeMillis()>=120000)throw new ChatFailure(409,"RECOVERY_DEADLINE_EXCEEDED");
        }else if(t.deadline()==null||!t.deadline().isAfter(c.recovery().now()))throw new ChatFailure(409,"RECOVERY_DEADLINE_EXCEEDED");
        // Reuse the existing frozen binding/credential policy rather than substituting a key.
        var authorization=c.conversation().selectAuthorizeChatCredential25(t.id());
        if(authorization.isEmpty()||!authorization.getFirst().value1()||!"ACTIVE".equals(authorization.getFirst().value2()))throw new ChatFailure(403,"CREDENTIAL_REVOKED");
        var config=object(c.conversation().selectTask6(t.id(),t.spaceId()).getFirst().value1());
        validateModel(c,UUID.fromString(String.valueOf(config.get("modelId"))));
        if(!ChatRuntime.bundle().hash().equals(cp.bundleHash()))throw new ChatFailure(409,"BUNDLE_INCOMPATIBLE");
        if(verifyPrivateState)verifySnapshot(c,t,cp.id(),ChatRuntime.bundle());
    }
    public Recovery recovery(Actor actor,UUID id) {return tx(true,c->{auth(c,actor);task(c,actor,id,false);return projection(c,id);});}
    public Recovery recoveryForOperator(UUID id) {return tx(true,c->projection(c,id));}
    private static Recovery projection(ChatMappers c,UUID id) {
        var t=c.recovery().task(id);if(t==null)throw new ChatFailure(404,"RESOURCE_NOT_FOUND");
        var cp=c.recovery().latestMetadata(id);String reason=null;boolean eligible=false;
        try {core(c,t,cp,false,false);eligible=true;reason="OPERATOR_VALIDATION_REQUIRED";}catch(ChatFailure denied){reason=denied.code();}
        var request=c.recovery().latestRequest(id);
        String stage=eligible?"AVAILABLE":c.recovery().live(id)>0?"RUNNING":"SUCCEEDED".equals(t.status())?"COMPLETED":"BLOCKED";
        if(request!=null&&"QUEUED".equals(request.status())&&"QUEUED".equals(t.status()))stage="QUEUED";
        else if(request!=null&&"CLAIMED".equals(request.status())&&"RUNNING".equals(t.status()))stage="RESTORING";
        if(Set.of("QUEUED","RESTORING","COMPLETED").contains(stage))reason=null;
        return new Recovery(id,eligible,stage,reason,t.revision(),t.version(),cp==null?null:cp.id(),t.traceId());
    }
    public Recovery restore(RestoreCommand command) {return tx(c->{
        if(command==null||command.commandId()==null||command.taskId()==null||command.checkpointId()==null||command.stopAttemptId()==null||command.operator()==null||command.operator().isBlank()||command.operator().length()>128)
            throw new ChatFailure(422,"RECOVERY_COMMAND_INVALID");
        String digest=hash(canonicalJson(command));var t=locked(c,command.taskId());
        var existing=c.recovery().request(command.commandId());
        if(existing!=null){if(!digest.equals(existing.requestHash()))throw new ChatFailure(409,"IDEMPOTENCY_CONFLICT");return projection(c,t.id());}
        if(t.version()!=command.expectedTaskVersion()||t.revision()!=command.expectedRevision())throw new ChatFailure(409,"RECOVERY_VERSION_CONFLICT");
        var cp=c.recovery().latest(t.id());if(cp==null||!cp.id().equals(command.checkpointId())||!Objects.equals(c.recovery().latestAttempt(t.id()),command.stopAttemptId()))throw new ChatFailure(409,"RECOVERY_EVIDENCE_MISMATCH");
        core(c,t,cp,false,true);
        var config=object(c.conversation().selectTask6(t.id(),t.spaceId()).getFirst().value1());
        long tokens=0,cost=0;
        if(!daily(c,t.id())){
            var available=c.executionPolicy().legacyAvailable(t.id());tokens=available.tokens();cost=available.cost();
            if(c.recovery().reserve(t.spaceId(),t.userId(),tokens,cost)!=1)throw new ChatFailure(429,"QUOTA_EXCEEDED");
        }
        long revision=t.revision()+1;c.recovery().insertRequest(command,t.spaceId(),revision,digest);
        c.recovery().queueTask(t.id(),revision,tokens,cost);c.recovery().queueDispatch(t.id());
        var actor=new Actor(t.userId(),t.spaceId());outbox(c,actor,t.id(),c.recovery().generation(t.id()));
        event(c,actor,t.conversationId(),t.id(),"task.state_changed",task(c,actor,t.id(),false).version(),Map.of("taskId",t.id().toString(),"status","QUEUED"));
        audit(c,actor,"CHECKPOINT_RECOVERY_REQUESTED",t.id(),t.traceId());return projection(c,t.id());
    });}
    static boolean admitClaim(ChatMappers c,Actor actor,UUID id) {
        var request=c.recovery().latestRequest(id);if(request==null||!"QUEUED".equals(request.status()))return true;
        try {core(c,c.recovery().task(id),c.recovery().checkpoint(request.checkpointId(),id),true,true);return true;}
        catch(ChatFailure refused) {
            releaseRemaining(c,actor,id);c.recovery().rejectRequest(request.commandId());
            c.conversation().updateTask135("RECOVERY_DEADLINE_EXCEEDED".equals(refused.code())?"FAILED":"BLOCKED",refused.code(),id);
            c.dispatch().updateExecutionDispatch152(id);c.recovery().rejectMessage(id);
            assistantTerminalEvent(c,actor,c.recovery().task(id).conversationId(),id);
            event(c,actor,c.recovery().task(id).conversationId(),id,"task.state_changed",task(c,actor,id,false).version(),Map.of("taskId",id.toString(),"status",c.recovery().task(id).status(),"reasonCode",refused.code()));
            return false;
        }
    }
    static void claimed(ChatMappers c,Lease l) {
        c.recovery().initializeDeadline(l.taskId());var request=c.recovery().latestRequest(l.taskId());
        if(request==null||!"QUEUED".equals(request.status()))return;
        if(c.recovery().claimRequest(request.commandId(),l.attemptId())!=1)throw new ChatFailure(409,"RECOVERY_VERSION_CONFLICT");
        var resume=c.plan().queuedResume(l.taskId());
        if(resume!=null){
            if(!resume.checkpointId().equals(request.checkpointId())||resume.generation()!=c.recovery().generation(l.taskId())
                    ||c.plan().claimResume(resume.id(),l.attemptId())!=1)throw new ChatFailure(409,"CONTROL_RESUME_BINDING_MISMATCH");
        }
        copy(c,l,request.checkpointId());
    }
    static void requireTime(ChatMappers c,Lease l) {
        var t=c.recovery().task(l.taskId());
        if(t.deadline()==null||!t.deadline().isAfter(c.recovery().now()))throw new ChatFailure(504,"PROVIDER_TIMEOUT");
    }
}
