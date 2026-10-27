package com.agentplatform.infrastructure.adapter.repository.chat;

import com.agentplatform.domain.chat.ChatData.Lease;
import com.agentplatform.domain.chat.ChatFailure;
import com.agentplatform.domain.chat.ExecutionLedger.*;
import com.agentplatform.types.Uuid7;
import com.agentplatform.types.harness.HarnessData.*;
import java.nio.charset.StandardCharsets;
import com.agentplatform.infrastructure.dao.chat.ChatMappers;
import com.agentplatform.infrastructure.dao.po.ChatProjections.*;
import java.time.Instant;
import java.util.*;
import static com.agentplatform.infrastructure.adapter.repository.chat.ChatPersistenceSupport.*;

/** All methods run on the repository's existing connection: no nested/independent transactions. */
final class MyBatisExecutionLedger {
    private MyBatisExecutionLedger() {}

    static UUID beginTurn(ChatMappers c, Lease l) {
        guard(c,l);
        var open=c.recovery().turns(l.taskId()).stream().filter(t->"OPEN".equals(t.status())).toList();
        if(open.size()>1)throw new ChatFailure(409,"LOGICAL_TURN_CONFLICT");
        if(!open.isEmpty()){c.recovery().bindTurn(l,open.getFirst().id());return open.getFirst().id();}
        UUID id=Uuid7.next();String input=canonicalJson(RecoveryPersistence.history(c,l));
        c.ledger().insertModelTurn188(id,l.spaceId(),l.taskId(),l.attemptId(),l.fence(),input,hash(input),l.taskId());
        c.recovery().bindTurn(l,id);return id;
    }

    static void guard(ChatMappers c,Lease l) {
        owned(c,l,false);authorizeCredential(c,l);activeModel(c,l);
    }

    static void commitRound(ChatMappers c,Lease l,UUID id,Turn turn,CallResult accepted) {
        guard(c,l);
        String body=canonicalJson(turn.round()), fingerprint=hash(body),status;
        { var rows185 = c.ledger().selectModelTurn185(id, l.spaceId(), l.taskId(), l.attemptId(), l.fence()); var row185 = rows185.isEmpty() ? null : rows185.getFirst();
            if(row185 == null)throw new ChatFailure(409,"TURN_NOT_FOUND");status=row185.value1();
            if(row185.value3()!=turn.ordinal())throw new ChatFailure(409,"TURN_ORDINAL_MISMATCH");
            if("COMMITTED".equals(status)) {
                if(!fingerprint.equals(row185.value2())||!accepted.call().id().equals(row185.value4()))throw new ChatFailure(409,"TURN_CONTENT_CONFLICT");
                return;
            }
         }
        { var rows184 = c.accounting().selectModelCall184(accepted.call().id(), id, l.taskId(), l.attemptId());
            if(rows184.isEmpty())throw new ChatFailure(409,"CALL_NOT_STARTED");
         }
        Set<String> ids=new HashSet<>();
        for(var call:turn.round().calls()) {
            if(!ids.add(call.id()))throw new ChatFailure(409,"DUPLICATE_TOOL_CALL");
            UUID operation=Uuid7.next();c.ledger().insertToolOperation189(operation, l.spaceId(), l.taskId(), l.attemptId(), id, l.fence(), call.id(), canonicalJson(call), hash(canonicalJson(call)));
            c.recovery().bindOperation(l,operation);
        }
        c.ledger().updateModelTurn190(UUID.fromString(turn.id()), body, fingerprint, accepted.call().id(), id);
        settleCall(c,l,accepted.call(),accepted.usageKnown()?turn.round():null,accepted.requestId(),accepted.usageKnown()?null:"PROVIDER_USAGE_MISSING",accepted.real());
    }

    static void startTool(ChatMappers c,Lease l,com.agentplatform.types.harness.HarnessData.Call call) {
        guard(c,l);
        if(PermissionPersistence.decision(c,l,call,true)!=Decision.ALLOW)throw new ChatFailure(403,"OPERATION_NOT_AUTHORIZED");
        ApprovalPersistence.consume(c,l,call);
        if(c.ledger().updateToolOperation191(l.spaceId(), l.taskId(), l.attemptId(), l.fence(), call.id(), hash(canonicalJson(call)))!=1)
            throw new ChatFailure(409,"OPERATION_NOT_PREPARED");
    }

    static void commitTool(ChatMappers c,Lease l,com.agentplatform.types.harness.HarnessData.Call call,Observation result) {
        if(result.status()==OperationStatus.DENIED){ApprovalPersistence.denied(c,l,call,result);return;}
        guard(c,l);
        if(!call.id().equals(result.callId())||!call.name().equals(result.name())||!Set.of(OperationStatus.SUCCEEDED,OperationStatus.FAILED).contains(result.status()))
            throw new ChatFailure(409,"OPERATION_RESULT_MISMATCH");
        String body=canonicalJson(result),digest=hash(body);
        { var rows183 = c.ledger().selectToolOperation183(l.spaceId(), l.taskId(), l.attemptId(), l.fence(), call.id(), hash(canonicalJson(call))); var row183 = rows183.isEmpty() ? null : rows183.getFirst();
            if(row183 == null)throw new ChatFailure(409,"OPERATION_NOT_FOUND");
            if(result.status().name().equals(row183.value1())&&digest.equals(row183.value2()))return;
            if(!"RUNNING".equals(row183.value1()))throw new ChatFailure(409,"OPERATION_NOT_RUNNING");
         }
        c.ledger().updateToolOperation192(body, digest, l.taskId(), call.id(),result.status().name());
    }

    static String key(Lease l,String key) {
        if(key==null||key.isBlank()||key.length()>211)throw new ChatFailure(400,"INVALID_STATE_KEY");
        return "attempt/"+l.attemptId()+"/"+key;
    }
    static StateSlot state(ChatMappers c,Lease l,String key) {
        guard(c,l);
        { var rows182 = c.ledger().selectStateSlot182(l.spaceId(), l.userId(), l.conversationId(), key(l,key)); var row182 = rows182.isEmpty() ? null : rows182.getFirst();
            if(row182 == null)return new StateSlot(0,null);
            if(!"2.0.4".equals(row182.value3()))throw new ChatFailure(409,"STATE_FRAMEWORK_INCOMPATIBLE");
            return new StateSlot(row182.value1(),row182.value2().getBytes(StandardCharsets.UTF_8));
         }
    }
    static long stateCas(ChatMappers c,Lease l,String key,long version,byte[] body) {
        guard(c,l);
        if(version<0||body==null||body.length>1024*1024)throw new ChatFailure(400,"INVALID_STATE_CAS");
        String payload=new String(body,StandardCharsets.UTF_8);long result;
        if(version==0) {
            { var rows181 = c.ledger().insertStateSlot181(l.spaceId(), l.userId(), l.conversationId(), key(l,key), payload); var row181 = rows181.isEmpty() ? null : rows181.getFirst();
                if(row181 == null)throw new ChatFailure(409,"STATE_CAS_CONFLICT");result=row181.value1();
             }
        } else {
            { var rows180 = c.ledger().updateStateSlot180(payload, l.spaceId(), l.userId(), l.conversationId(), key(l,key), version); var row180 = rows180.isEmpty() ? null : rows180.getFirst();
                if(row180 == null)throw new ChatFailure(409,"STATE_CAS_CONFLICT");result=row180.value1();
             }
        }
        return result;
    }
    static Set<String> stateKeys(ChatMappers c,Lease l) {
        guard(c,l);Set<String> result=new HashSet<>();String prefix="attempt/"+l.attemptId()+"/";
        { var rows179 = c.ledger().selectStateSlot179(l.spaceId(), l.userId(), l.conversationId(), prefix);
            for (var row179 : rows179)result.add(row179.value1().substring(prefix.length()));
         }return Set.copyOf(result);
    }
    static void stateDelete(ChatMappers c,Lease l,String key) {
        guard(c,l);c.ledger().deleteStateSlot193(l.spaceId(), l.userId(), l.conversationId(), key(l,key));
    }

    static UUID seal(ChatMappers c,Lease l,Bundle bundle) {
        guard(c,l);verifyBundle(c,l,bundle);
        Map<String,Object> states=new TreeMap<>();
        Set<String> keys=stateKeys(c,l);boolean snapshot=keys.contains("checkpoint_agent_state");
        for(String key:keys) {
            if(snapshot&&"agent_state".equals(key))continue;
            StateSlot slot=state(c,l,key);
            if(slot.body()==null)throw new ChatFailure(409,"CHECKPOINT_STATE_MISSING");
            String bytes=Base64.getEncoder().encodeToString(slot.body());
            states.put("checkpoint_agent_state".equals(key)?"agent_state":key,Map.of("sourceKey",key,"version",slot.version(),"body",bytes,"hash",hash(new String(slot.body(),StandardCharsets.UTF_8))));
        }
        if(states.isEmpty())throw new ChatFailure(409,"CHECKPOINT_STATE_MISSING");
        List<Object> rounds=new ArrayList<>(),operations=new ArrayList<>();long watermark=0;
        { var rows178 = c.ledger().selectModelTurn178(l.taskId());
            for (var row178 : rows178) {var round=object(row178.value2());if(!hash(canonicalJson(round)).equals(row178.value4()))throw new ChatFailure(409,"LEDGER_HASH_MISMATCH");
                Object input=JSON.readValue(row178.value5(),Object.class);if(!hash(canonicalJson(input)).equals(row178.value6()))throw new ChatFailure(409,"LEDGER_HASH_MISMATCH");
                rounds.add(Map.of("id",row178.value1().toString(),"round",round,"input",input));watermark++;}
         }
        if(rounds.isEmpty())throw new ChatFailure(409,"CHECKPOINT_ROUND_MISSING");
        { var rows177 = c.ledger().selectToolOperation177(l.taskId());
            for (var row177 : rows177){var value=new TreeMap<String,Object>();value.put("call",object(row177.value1()));value.put("status",row177.value2());value.put("result",row177.value3()==null?null:object(row177.value3()));operations.add(value);}
         }
        String canonical=canonicalJson(Map.of("rounds",rounds,"operations",operations));
        String state=canonicalJson(states);
        String manifest=canonicalJson(Map.of("bundle",bundle,"watermark",watermark,"canonicalHash",hash(canonical),"stateHash",hash(state),"states",stateManifest(states)));UUID id=Uuid7.next();
        c.ledger().insertCheckpoint194(id, l.spaceId(), l.taskId(), l.attemptId(), l.fence(), WorkspaceToolPersistence.bundleId(c,l.spaceId(),l.taskId()), bundle.hash(), manifest, hash(manifest), canonical, hash(canonical), watermark, state, hash(state));return id;
    }
    static List<Message> history(ChatMappers c,Lease l) {
        guard(c,l);List<Message> history=new ArrayList<>();
        { var rows176 = c.ledger().selectModelTurn176(l.taskId());
            for (var row176 : rows176) {
                if(history.isEmpty())history.addAll(List.of(JSON.readValue(row176.value2(),Message[].class)));
                Round round=JSON.readValue(row176.value3(),Round.class);history.add(new Message(Role.ASSISTANT,round.text(),round.calls(),List.of()));
                List<Observation> observations=new ArrayList<>();
                { var rows175 = c.ledger().selectToolOperation175(row176.value1());
                    for (var row175 : rows175)observations.add(JSON.readValue(row175.value1(),Observation.class));
                 }
                if(!observations.isEmpty())history.add(new Message(Role.TOOL,"",List.of(),observations));
            }
         }
        if(history.isEmpty())throw new ChatFailure(409,"CHECKPOINT_ROUND_MISSING");return List.copyOf(history);
    }
    static void verifyBundle(ChatMappers c,Lease l,Bundle bundle) {
        { var rows174 = c.conversation().selectTask174(l.taskId(), l.spaceId()); var row174 = rows174.isEmpty() ? null : rows174.getFirst();
            if(row174 == null||!row174.value1().equals(bundle.hash()))throw new ChatFailure(409,"BUNDLE_INCOMPATIBLE");
         }
    }
    static Map<String,Object> checkpoint(ChatMappers c,Lease l,UUID id,Bundle bundle) {
        guard(c,l);verifyBundle(c,l,bundle);
        try { var rows173 = c.ledger().selectCheckpoint173(id, l.spaceId(), l.taskId()); var row173 = rows173.isEmpty() ? null : rows173.getFirst();
            if(row173 == null)throw new ChatFailure(409,"CHECKPOINT_NOT_COMMITTED");
            Map<String,Object> manifest=object(row173.manifestJson());
            if(!bundle.hash().equals(row173.bundleHash())||!canonicalJson(bundle).equals(canonicalJson(manifest.get("bundle"))))throw new ChatFailure(409,"BUNDLE_INCOMPATIBLE");
            for(var component : List.of(List.of(row173.manifestJson(), row173.manifestHash()),
                    List.of(row173.canonicalJson(), row173.canonicalHash()), List.of(row173.stateJson(), row173.stateHash()))) {
                if (!hash(canonicalJson(object(component.get(0)))).equals(component.get(1))) throw new ChatFailure(409,"CHECKPOINT_HASH_MISMATCH");
            }
            Map<String,Object> states=object(row173.stateJson());if(states.isEmpty())throw new ChatFailure(409,"CHECKPOINT_STATE_MISSING");
            Map<String,Object> canonical=object(row173.canonicalJson());
            if(((Number)manifest.get("watermark")).longValue()!=row173.watermark()||!(canonical.get("rounds") instanceof List<?> rounds)||rounds.size()!=row173.watermark()
                    ||!row173.canonicalHash().equals(manifest.get("canonicalHash"))||!row173.stateHash().equals(manifest.get("stateHash"))
                    ||!canonicalJson(stateManifest(states)).equals(canonicalJson(manifest.get("states"))))throw new ChatFailure(409,"CHECKPOINT_HASH_MISMATCH");
            for(Object value:states.values()) {
                @SuppressWarnings("unchecked") var slot=(Map<String,Object>)value;
                String encoded=(String)slot.get("body");
                if(!hash(new String(Base64.getDecoder().decode(encoded),StandardCharsets.UTF_8)).equals(slot.get("hash"))||((Number)slot.get("version")).longValue()<1)throw new ChatFailure(409,"CHECKPOINT_HASH_MISMATCH");
                try{Base64.getDecoder().decode(encoded);}catch(IllegalArgumentException invalid){throw new ChatFailure(409,"CHECKPOINT_HASH_MISMATCH");}
            }
            return states;
         }catch(ChatFailure failure){throw failure;}catch(RuntimeException malformed){throw new ChatFailure(409,"CHECKPOINT_HASH_MISMATCH");}
    }
    static void restore(ChatMappers c,Lease l,UUID id,Bundle bundle) {
        Map<String,Object> states=checkpoint(c,l,id,bundle);
        { var rows172 = c.ledger().selectCheckpoint172(id); var row172 = rows172.isEmpty() ? null : rows172.getFirst();
            if(row172 == null||l.attemptId().equals(row172.value1()))throw new ChatFailure(409,"CHECKPOINT_TARGET_NOT_NEW_ATTEMPT");
         }
        if(!stateKeys(c,l).isEmpty())throw new ChatFailure(409,"CHECKPOINT_TARGET_NOT_EMPTY");
        for(var item:states.entrySet()) {
            @SuppressWarnings("unchecked") var slot=(Map<String,Object>)item.getValue();
            stateCas(c,l,item.getKey(),0,Base64.getDecoder().decode((String)slot.get("body")));
        }
    }
    private static Map<String,Object> stateManifest(Map<String,Object> states) {
        Map<String,Object> manifest=new TreeMap<>();
        states.forEach((key,value)->{
            @SuppressWarnings("unchecked") var slot=(Map<String,Object>)value;
            manifest.put(key,Map.of("sourceKey",slot.get("sourceKey"),"version",slot.get("version"),"hash",slot.get("hash")));
        });return manifest;
    }

    static Object sorted(Object value) {
        if(value instanceof Map<?,?> map){var sorted=new TreeMap<String,Object>();map.forEach((k,v)->sorted.put(k.toString(),sorted(v)));return sorted;}
        if(value instanceof List<?> list)return list.stream().map(MyBatisExecutionLedger::sorted).toList();return value;
    }
    static String canonicalJson(Object value) {
        // Convert records before sorting; jsonb whitespace/key order must not affect fingerprints.
        return json(sorted(JSON.readValue(json(value),Object.class)));
    }
}
