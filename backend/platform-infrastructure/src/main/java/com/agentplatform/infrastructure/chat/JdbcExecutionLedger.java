package com.agentplatform.infrastructure.chat;

import com.agentplatform.domain.chat.ChatData.Lease;
import com.agentplatform.domain.chat.ChatFailure;
import com.agentplatform.domain.chat.ExecutionLedger.*;
import com.agentplatform.types.Uuid7;
import com.agentplatform.types.harness.HarnessData.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static com.agentplatform.infrastructure.chat.JdbcChatRepository.*;

/** All methods run on the repository's existing connection: no nested/independent transactions. */
final class JdbcExecutionLedger {
    private JdbcExecutionLedger() {}

    static UUID beginTurn(Connection c, Lease l) throws SQLException {
        guard(c,l);
        try(var q=q(c,"SELECT id FROM pf.model_turn WHERE attempt_id=? AND status='OPEN'",l.attemptId());var r=q.executeQuery()) {
            if(r.next())return r.getObject(1,UUID.class);
        }
        UUID id=Uuid7.next();
        List<Message> history=new ArrayList<>();history.add(Message.text(Role.SYSTEM,"You are a helpful assistant. Answer ordinary text questions. No tools or filesystem access are available."));
        try(var s=q(c,"SELECT m.role,m.blocks_json->0->>'text' FROM pf.message m JOIN pf.task t ON t.id=m.task_id WHERE m.space_id=? AND m.conversation_id=? AND ((t.status='SUCCEEDED' AND m.status='COMPLETED') OR (t.id=? AND m.role='USER')) ORDER BY t.created_at,t.id,CASE m.role WHEN 'USER' THEN 0 ELSE 1 END,m.id",l.spaceId(),l.conversationId(),l.taskId());var r=s.executeQuery()) {
            while(r.next())history.add(Message.text(Role.valueOf(r.getString(1)),r.getString(2)));
        }
        String input=canonicalJson(history);
        exec(c,"INSERT INTO pf.model_turn(id,space_id,task_id,attempt_id,fencing_token,ordinal,input_json,input_hash) SELECT ?,?,?,?,?,coalesce(max(ordinal),0)+1,?::jsonb,? FROM pf.model_turn WHERE attempt_id=?",
                id,l.spaceId(),l.taskId(),l.attemptId(),l.fence(),input,hash(input),l.attemptId());
        return id;
    }

    static void guard(Connection c,Lease l)throws SQLException {
        owned(c,l,false);authorizeCredential(c,l);activeModel(c,l);
    }

    static void commitRound(Connection c,Lease l,UUID id,Turn turn,CallResult accepted)throws SQLException {
        guard(c,l);
        String body=canonicalJson(turn.round()), fingerprint=hash(body),status;
        try(var s=q(c,"SELECT status,content_hash,ordinal,accepted_call_id FROM pf.model_turn WHERE id=? AND space_id=? AND task_id=? AND attempt_id=? AND fencing_token=? FOR UPDATE",
                id,l.spaceId(),l.taskId(),l.attemptId(),l.fence());var r=s.executeQuery()) {
            if(!r.next())throw new ChatFailure(409,"TURN_NOT_FOUND");status=r.getString(1);
            if(r.getLong(3)!=turn.ordinal())throw new ChatFailure(409,"TURN_ORDINAL_MISMATCH");
            if("COMMITTED".equals(status)) {
                if(!fingerprint.equals(r.getString(2))||!accepted.call().id().equals(r.getObject(4,UUID.class)))throw new ChatFailure(409,"TURN_CONTENT_CONFLICT");
                return;
            }
        }
        try(var s=q(c,"SELECT 1 FROM pf.model_call WHERE id=? AND turn_id=? AND task_id=? AND attempt_id=? AND status='STARTED' FOR UPDATE",
                accepted.call().id(),id,l.taskId(),l.attemptId());var r=s.executeQuery()) {
            if(!r.next())throw new ChatFailure(409,"CALL_NOT_STARTED");
        }
        Set<String> ids=new HashSet<>();
        for(var call:turn.round().calls()) {
            if(!ids.add(call.id()))throw new ChatFailure(409,"DUPLICATE_TOOL_CALL");
            exec(c,"INSERT INTO pf.tool_operation(id,space_id,task_id,attempt_id,turn_id,fencing_token,call_id,call_json,call_hash) VALUES(?,?,?,?,?,?,?,?::jsonb,?)",
                    Uuid7.next(),l.spaceId(),l.taskId(),l.attemptId(),id,l.fence(),call.id(),canonicalJson(call),hash(canonicalJson(call)));
        }
        exec(c,"UPDATE pf.model_turn SET status='COMMITTED',sdk_turn_id=?,round_json=?::jsonb,content_hash=?,accepted_call_id=?,committed_at=now() WHERE id=?",
                UUID.fromString(turn.id()),body,fingerprint,accepted.call().id(),id);
        settleCall(c,l,accepted.call(),accepted.usageKnown()?turn.round():null,accepted.requestId(),accepted.usageKnown()?null:"PROVIDER_USAGE_MISSING",accepted.real());
    }

    static void startTool(Connection c,Lease l,com.agentplatform.types.harness.HarnessData.Call call)throws SQLException {
        guard(c,l);
        if(exec(c,"UPDATE pf.tool_operation SET status='RUNNING' WHERE space_id=? AND task_id=? AND attempt_id=? AND fencing_token=? AND call_id=? AND call_hash=? AND status='PREPARED'",
                l.spaceId(),l.taskId(),l.attemptId(),l.fence(),call.id(),hash(canonicalJson(call)))!=1)
            throw new ChatFailure(409,"OPERATION_NOT_PREPARED");
    }

    static void commitTool(Connection c,Lease l,com.agentplatform.types.harness.HarnessData.Call call,Observation result)throws SQLException {
        guard(c,l);
        if(!call.id().equals(result.callId())||!call.name().equals(result.name())||result.status()!=OperationStatus.SUCCEEDED)
            throw new ChatFailure(409,"OPERATION_RESULT_MISMATCH");
        String body=canonicalJson(result),digest=hash(body);
        try(var s=q(c,"SELECT status,result_hash FROM pf.tool_operation WHERE space_id=? AND task_id=? AND attempt_id=? AND fencing_token=? AND call_id=? AND call_hash=? FOR UPDATE",
                l.spaceId(),l.taskId(),l.attemptId(),l.fence(),call.id(),hash(canonicalJson(call)));var r=s.executeQuery()) {
            if(!r.next())throw new ChatFailure(409,"OPERATION_NOT_FOUND");
            if("SUCCEEDED".equals(r.getString(1))&&digest.equals(r.getString(2)))return;
            if(!"RUNNING".equals(r.getString(1)))throw new ChatFailure(409,"OPERATION_NOT_RUNNING");
        }
        exec(c,"UPDATE pf.tool_operation SET status='SUCCEEDED',result_json=?::jsonb,result_hash=?,ended_at=now() WHERE task_id=? AND call_id=?",body,digest,l.taskId(),call.id());
    }

    static String key(Lease l,String key) {
        if(key==null||key.isBlank()||key.length()>211)throw new ChatFailure(400,"INVALID_STATE_KEY");
        return "attempt/"+l.attemptId()+"/"+key;
    }
    static StateSlot state(Connection c,Lease l,String key)throws SQLException {
        guard(c,l);
        try(var s=q(c,"SELECT version,payload_json::text,framework_version FROM pf_runtime.state_slot WHERE space_id=? AND user_id=? AND conversation_id=? AND state_key=?",
                l.spaceId(),l.userId(),l.conversationId(),key(l,key));var r=s.executeQuery()) {
            if(!r.next())return new StateSlot(0,null);
            if(!"2.0.4".equals(r.getString(3)))throw new ChatFailure(409,"STATE_FRAMEWORK_INCOMPATIBLE");
            return new StateSlot(r.getLong(1),r.getString(2).getBytes(StandardCharsets.UTF_8));
        }
    }
    static long stateCas(Connection c,Lease l,String key,long version,byte[] body)throws SQLException {
        guard(c,l);
        if(version<0||body==null||body.length>1024*1024)throw new ChatFailure(400,"INVALID_STATE_CAS");
        String payload=new String(body,StandardCharsets.UTF_8);long result;
        if(version==0) {
            try(var s=q(c,"INSERT INTO pf_runtime.state_slot(space_id,user_id,conversation_id,state_key,version,framework_version,payload_json) VALUES(?,?,?,?,1,'2.0.4',?::jsonb) ON CONFLICT DO NOTHING RETURNING version",
                    l.spaceId(),l.userId(),l.conversationId(),key(l,key),payload);var r=s.executeQuery()) {
                if(!r.next())throw new ChatFailure(409,"STATE_CAS_CONFLICT");result=r.getLong(1);
            }
        } else {
            try(var s=q(c,"UPDATE pf_runtime.state_slot SET payload_json=?::jsonb,version=version+1,updated_at=now() WHERE space_id=? AND user_id=? AND conversation_id=? AND state_key=? AND version=? AND framework_version='2.0.4' RETURNING version",
                    payload,l.spaceId(),l.userId(),l.conversationId(),key(l,key),version);var r=s.executeQuery()) {
                if(!r.next())throw new ChatFailure(409,"STATE_CAS_CONFLICT");result=r.getLong(1);
            }
        }
        return result;
    }
    static Set<String> stateKeys(Connection c,Lease l)throws SQLException {
        guard(c,l);Set<String> result=new HashSet<>();String prefix="attempt/"+l.attemptId()+"/";
        try(var s=q(c,"SELECT state_key FROM pf_runtime.state_slot WHERE space_id=? AND user_id=? AND conversation_id=? AND starts_with(state_key,?)",
                l.spaceId(),l.userId(),l.conversationId(),prefix);var r=s.executeQuery()) {
            while(r.next())result.add(r.getString(1).substring(prefix.length()));
        }return Set.copyOf(result);
    }
    static void stateDelete(Connection c,Lease l,String key)throws SQLException {
        guard(c,l);exec(c,"DELETE FROM pf_runtime.state_slot WHERE space_id=? AND user_id=? AND conversation_id=? AND state_key=?",
                l.spaceId(),l.userId(),l.conversationId(),key(l,key));
    }

    static UUID seal(Connection c,Lease l,Bundle bundle)throws SQLException {
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
        try(var s=q(c,"SELECT id,round_json,ordinal,content_hash,input_json,input_hash FROM pf.model_turn WHERE task_id=? AND status='COMMITTED' ORDER BY committed_at,id",l.taskId());var r=s.executeQuery()) {
            while(r.next()) {var round=object(r.getString(2));if(!hash(canonicalJson(round)).equals(r.getString(4)))throw new ChatFailure(409,"LEDGER_HASH_MISMATCH");
                Object input=JSON.readValue(r.getString(5),Object.class);if(!hash(canonicalJson(input)).equals(r.getString(6)))throw new ChatFailure(409,"LEDGER_HASH_MISMATCH");
                rounds.add(Map.of("id",r.getObject(1).toString(),"round",round,"input",input));watermark++;}
        }
        if(rounds.isEmpty())throw new ChatFailure(409,"CHECKPOINT_ROUND_MISSING");
        try(var s=q(c,"SELECT call_json,status,result_json FROM pf.tool_operation WHERE task_id=? ORDER BY created_at,id",l.taskId());var r=s.executeQuery()) {
            while(r.next()){var value=new TreeMap<String,Object>();value.put("call",object(r.getString(1)));value.put("status",r.getString(2));value.put("result",r.getString(3)==null?null:object(r.getString(3)));operations.add(value);}
        }
        String canonical=canonicalJson(Map.of("rounds",rounds,"operations",operations));
        String state=canonicalJson(states);
        String manifest=canonicalJson(Map.of("bundle",bundle,"watermark",watermark,"canonicalHash",hash(canonical),"stateHash",hash(state),"states",stateManifest(states)));UUID id=Uuid7.next();
        exec(c,"INSERT INTO pf.checkpoint(id,space_id,task_id,attempt_id,fencing_token,bundle_id,bundle_hash,manifest_json,manifest_hash,canonical_json,canonical_hash,watermark,state_json,state_hash) VALUES(?,?,?,?,?,?,?,?::jsonb,?,?::jsonb,?,?,?::jsonb,?)",
                id,l.spaceId(),l.taskId(),l.attemptId(),l.fence(),BUNDLE,bundle.hash(),manifest,hash(manifest),canonical,hash(canonical),watermark,state,hash(state));return id;
    }
    static List<Message> history(Connection c,Lease l)throws SQLException {
        guard(c,l);List<Message> history=new ArrayList<>();
        try(var s=q(c,"SELECT id,input_json,round_json FROM pf.model_turn WHERE task_id=? AND status='COMMITTED' ORDER BY committed_at,id",l.taskId());var r=s.executeQuery()) {
            while(r.next()) {
                if(history.isEmpty())history.addAll(List.of(JSON.readValue(r.getString(2),Message[].class)));
                Round round=JSON.readValue(r.getString(3),Round.class);history.add(new Message(Role.ASSISTANT,round.text(),round.calls(),List.of()));
                List<Observation> observations=new ArrayList<>();
                try(var ops=q(c,"SELECT result_json FROM pf.tool_operation WHERE turn_id=? AND status='SUCCEEDED' ORDER BY created_at,id",r.getObject(1,UUID.class));var rows=ops.executeQuery()) {
                    while(rows.next())observations.add(JSON.readValue(rows.getString(1),Observation.class));
                }
                if(!observations.isEmpty())history.add(new Message(Role.TOOL,"",List.of(),observations));
            }
        }
        if(history.isEmpty())throw new ChatFailure(409,"CHECKPOINT_ROUND_MISSING");return List.copyOf(history);
    }
    static void verifyBundle(Connection c,Lease l,Bundle bundle)throws SQLException {
        try(var s=q(c,"SELECT b.manifest_hash FROM pf.task t JOIN pf.runtime_bundle b ON b.id=t.bundle_id WHERE t.id=? AND t.space_id=?",l.taskId(),l.spaceId());var r=s.executeQuery()) {
            if(!r.next()||!r.getString(1).equals(bundle.hash()))throw new ChatFailure(409,"BUNDLE_INCOMPATIBLE");
        }
    }
    static Map<String,Object> checkpoint(Connection c,Lease l,UUID id,Bundle bundle)throws SQLException {
        guard(c,l);verifyBundle(c,l,bundle);
        try(var s=q(c,"SELECT * FROM pf.checkpoint WHERE id=? AND space_id=? AND task_id=? AND status='COMMITTED'",id,l.spaceId(),l.taskId());var r=s.executeQuery()) {
            if(!r.next())throw new ChatFailure(409,"CHECKPOINT_NOT_COMMITTED");
            Map<String,Object> manifest=object(r.getString("manifest_json"));
            if(!bundle.hash().equals(r.getString("bundle_hash"))||!canonicalJson(bundle).equals(canonicalJson(manifest.get("bundle"))))throw new ChatFailure(409,"BUNDLE_INCOMPATIBLE");
            for(String field:List.of("manifest","canonical","state"))if(!hash(canonicalJson(object(r.getString(field+"_json")))).equals(r.getString(field+"_hash")))throw new ChatFailure(409,"CHECKPOINT_HASH_MISMATCH");
            Map<String,Object> states=object(r.getString("state_json"));if(states.isEmpty())throw new ChatFailure(409,"CHECKPOINT_STATE_MISSING");
            Map<String,Object> canonical=object(r.getString("canonical_json"));
            if(((Number)manifest.get("watermark")).longValue()!=r.getLong("watermark")||!(canonical.get("rounds") instanceof List<?> rounds)||rounds.size()!=r.getLong("watermark")
                    ||!r.getString("canonical_hash").equals(manifest.get("canonicalHash"))||!r.getString("state_hash").equals(manifest.get("stateHash"))
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
    static void restore(Connection c,Lease l,UUID id,Bundle bundle)throws SQLException {
        Map<String,Object> states=checkpoint(c,l,id,bundle);
        try(var s=q(c,"SELECT attempt_id FROM pf.checkpoint WHERE id=?",id);var r=s.executeQuery()){
            if(!r.next()||l.attemptId().equals(r.getObject(1,UUID.class)))throw new ChatFailure(409,"CHECKPOINT_TARGET_NOT_NEW_ATTEMPT");
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
        if(value instanceof List<?> list)return list.stream().map(JdbcExecutionLedger::sorted).toList();return value;
    }
    static String canonicalJson(Object value) {
        // Convert records before sorting; jsonb whitespace/key order must not affect fingerprints.
        return json(sorted(JSON.readValue(json(value),Object.class)));
    }
}
