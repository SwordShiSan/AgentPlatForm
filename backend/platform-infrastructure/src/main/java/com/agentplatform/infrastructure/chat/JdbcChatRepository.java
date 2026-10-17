package com.agentplatform.infrastructure.chat;

import com.agentplatform.domain.chat.*;
import com.agentplatform.domain.chat.ChatData.*;
import com.agentplatform.types.Uuid7;
import com.agentplatform.types.harness.HarnessData;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Transaction boundaries are short; no provider or broker I/O inside a JDBC transaction. */
public final class JdbcChatRepository implements ChatRepository {
    public static final UUID BUNDLE=UUID.fromString("0199e000-0000-7000-8000-000000000001");
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final String url,user,password;
    private final javax.sql.DataSource dataSource;
    public JdbcChatRepository(String url,String user,String password){this.url=url;this.user=user;this.password=password;this.dataSource=null;}
    public JdbcChatRepository(javax.sql.DataSource dataSource){this.url=null;this.user=null;this.password=null;this.dataSource=java.util.Objects.requireNonNull(dataSource);}
    @FunctionalInterface private interface Work<T>{T run(Connection c)throws SQLException;}
    private <T>T tx(Work<T> work){return tx(false,work);}
    private <T>T tx(boolean snapshot,Work<T> work){
        try(var c=dataSource==null?DriverManager.getConnection(url,user,password):dataSource.getConnection()){
            c.setAutoCommit(false);if(snapshot)c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try{T v=work.run(c);c.commit();return v;}catch(SQLException|RuntimeException e){c.rollback();throw e;}
        }catch(SQLException e){
            if("23505".equals(e.getSQLState()))throw new ChatFailure(409,"RESOURCE_CONFLICT");
            if("40001".equals(e.getSQLState())||"40P01".equals(e.getSQLState()))throw new ChatFailure(409,"CONCURRENT_CHANGE");
            System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,"Chat database failure SQLSTATE="+e.getSQLState());
            throw new ChatFailure(503,"CHAT_STORAGE_UNAVAILABLE");
        }
    }
    private static PreparedStatement q(Connection c,String sql,Object...args)throws SQLException{
        var s=c.prepareStatement(sql);s.setQueryTimeout(5);
        for(int i=0;i<args.length;i++)s.setObject(i+1,args[i] instanceof Instant t?Timestamp.from(t):args[i]);return s;
    }
    private static int exec(Connection c,String sql,Object...args)throws SQLException{try(var s=q(c,sql,args)){return s.executeUpdate();}}
    private static String json(Object v){return JSON.writeValueAsString(v);}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> blocks(String value){return JSON.readValue(value,List.class);}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(String value){return JSON.readValue(value,Map.class);}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static void auth(Connection c,Actor a)throws SQLException{
        try(var s=q(c,"SELECT pf.authorize_chat_actor(?,?)",a.userId(),a.spaceId());var r=s.executeQuery()){
            if(!r.next()||!r.getBoolean(1))throw new ChatFailure(404,"RESOURCE_NOT_FOUND");
        }
    }
    private static void conversation(Connection c,Actor a,UUID id,boolean lock)throws SQLException{
        try(var s=q(c,"SELECT id FROM pf.conversation WHERE space_id=? AND id=?"+(lock?" FOR UPDATE":""),a.spaceId(),id);var r=s.executeQuery()){if(!r.next())throw new ChatFailure(404,"RESOURCE_NOT_FOUND");}
    }
    private static Task readTask(ResultSet r)throws SQLException{return new Task(r.getObject("id",UUID.class),r.getObject("conversation_id",UUID.class),r.getString("status"),r.getBoolean("cancel_requested"),r.getString("reason_code"),r.getLong("version"));}
    private static Conversation readConversation(ResultSet r)throws SQLException{return new Conversation(r.getObject("id",UUID.class),r.getString("title"),r.getLong("version"),r.getTimestamp("updated_at").toInstant());}
    private static Task task(Connection c,Actor a,UUID id,boolean lock)throws SQLException{
        try(var s=q(c,"SELECT * FROM pf.task WHERE space_id=? AND id=?"+(lock?" FOR UPDATE":""),a.spaceId(),id);var r=s.executeQuery()){if(!r.next())throw new ChatFailure(404,"RESOURCE_NOT_FOUND");return readTask(r);}
    }
    private static UUID idempotency(Connection c,Actor a,UUID key,String path,String fingerprint)throws SQLException{
        try(var s=q(c,"SELECT pg_advisory_xact_lock(?)",(long)(a+path+key).hashCode())){s.execute();}
        try(var s=q(c,"SELECT body_hash,resource_id FROM pf.idempotency_record WHERE user_id=? AND space_id=? AND method='POST' AND canonical_path=? AND key=?",a.userId(),a.spaceId(),path,key);var r=s.executeQuery()){
            if(!r.next())return null;if(!fingerprint.equals(r.getString(1)))throw new ChatFailure(409,"IDEMPOTENCY_CONFLICT");return r.getObject(2,UUID.class);
        }
    }
    private static void remember(Connection c,Actor a,UUID key,String path,String fingerprint,UUID resource)throws SQLException{
        exec(c,"INSERT INTO pf.idempotency_record(user_id,space_id,method,canonical_path,key,body_hash,resource_id) VALUES(?,?,'POST',?,?,?,?)",a.userId(),a.spaceId(),path,key,fingerprint,resource);
    }
    private static long event(Connection c,Actor a,UUID conversation,UUID task,String kind,long version,Map<String,Object> payload)throws SQLException{
        long seq;
        try(var s=q(c,"UPDATE pf.conversation SET next_seq=next_seq+1,updated_at=now(),version=version+1 WHERE space_id=? AND id=? RETURNING next_seq",a.spaceId(),conversation);var r=s.executeQuery()){if(!r.next())throw new ChatFailure(404,"RESOURCE_NOT_FOUND");seq=r.getLong(1);}
        exec(c,"INSERT INTO pf.platform_event(id,space_id,conversation_id,task_id,seq,type,entity_version,payload_json) VALUES(?,?,?,?,?,?,?,?::jsonb)",Uuid7.next(),a.spaceId(),conversation,task,seq,kind,version,json(payload));return seq;
    }
    private static List<Map<String,Object>> textBlocks(UUID id,String text){return List.of(Map.of("type","TEXT","blockId",id.toString(),"text",text));}
    private static void outbox(Connection c,Actor a,UUID task,long generation)throws SQLException{
        exec(c,"INSERT INTO pf.event_outbox(id,space_id,event_id,aggregate_id,generation) VALUES(?,?,?,?,?)",Uuid7.next(),a.spaceId(),Uuid7.next(),task,generation);
    }
    @Override public List<Model> models(Actor a){return tx(c->{auth(c,a);List<Model> result=new ArrayList<>();try(var st=q(c,"SELECT id,model_name,version FROM pf.model_definition WHERE status='ACTIVE' AND model_name IN ('deepseek-flash','deepseek-v4-pro') AND (EXISTS(SELECT 1 FROM pf.model_binding b JOIN pf.credential k ON k.id=b.credential_id WHERE b.model_id=pf.model_definition.id AND b.space_id=? AND b.user_id=? AND b.source_policy=k.scope_type AND k.status='ACTIVE' AND (k.scope_type='PLATFORM' OR (k.space_id=b.space_id AND k.owner_user_id=b.user_id))) OR EXISTS(SELECT 1 FROM pf.user_model_credential u JOIN pf.credential k ON k.id=u.credential_id WHERE u.space_id=? AND u.user_id=? AND k.scope_type='USER' AND k.space_id=u.space_id AND k.owner_user_id=u.user_id AND k.status='ACTIVE')) ORDER BY model_name,id",a.spaceId(),a.userId(),a.spaceId(),a.userId());var r=st.executeQuery()){while(r.next())result.add(new Model(r.getObject(1,UUID.class),r.getString(2),r.getLong(3)));}return List.copyOf(result);});}
    @Override public Conversation create(Actor a,UUID key,String title){return tx(c->{
        auth(c,a);String name=title==null?"普通对话":title;if(name.isBlank()||name.codePointCount(0,name.length())>120)throw new ChatFailure(400,"INVALID_TITLE");
        String fingerprint=hash(name);UUID id=idempotency(c,a,key,"/conversations",fingerprint);
        if(id==null){id=Uuid7.next();exec(c,"INSERT INTO pf.conversation(id,space_id,creator_id,title) VALUES(?,?,?,?)",id,a.spaceId(),a.userId(),name);remember(c,a,key,"/conversations",fingerprint,id);}
        try(var s=q(c,"SELECT * FROM pf.conversation WHERE space_id=? AND id=?",a.spaceId(),id);var r=s.executeQuery()){r.next();return readConversation(r);}
    });}
    @Override public List<Conversation> conversations(Actor a,UUID before,int limit){return tx(c->{auth(c,a);List<Conversation> values=new ArrayList<>();try(var s=q(c,"SELECT * FROM pf.conversation WHERE space_id=? AND (?::uuid IS NULL OR (created_at,id)<(SELECT created_at,id FROM pf.conversation WHERE space_id=? AND id=?)) ORDER BY created_at DESC,id DESC LIMIT ?",a.spaceId(),before,a.spaceId(),before,Math.min(100,Math.max(1,limit)));var r=s.executeQuery()){while(r.next())values.add(readConversation(r));}return List.copyOf(values);});}
    @Override public Conversation conversation(Actor a,UUID id){return tx(c->{auth(c,a);try(var s=q(c,"SELECT * FROM pf.conversation WHERE space_id=? AND id=?",a.spaceId(),id);var r=s.executeQuery()){if(!r.next())throw new ChatFailure(404,"RESOURCE_NOT_FOUND");return readConversation(r);}});}
    @Override public Snapshot snapshot(Actor a,UUID id){return tx(true,c->{
        auth(c,a);conversation(c,a,id,false);long cursor;
        try(var s=q(c,"SELECT next_seq FROM pf.conversation WHERE space_id=? AND id=?",a.spaceId(),id);var r=s.executeQuery()){r.next();cursor=r.getLong(1);}
        List<Message> messages=new ArrayList<>();List<Task> tasks=new ArrayList<>();
        try(var s=q(c,"SELECT * FROM pf.message WHERE space_id=? AND conversation_id=? ORDER BY created_at,id",a.spaceId(),id);var r=s.executeQuery()){while(r.next())messages.add(new Message(r.getObject("id",UUID.class),r.getObject("task_id",UUID.class),r.getString("role"),blocks(r.getString("blocks_json")),r.getString("status"),r.getLong("version")));}
        try(var s=q(c,"SELECT * FROM pf.task WHERE space_id=? AND conversation_id=? ORDER BY created_at,id",a.spaceId(),id);var r=s.executeQuery()){while(r.next())tasks.add(readTask(r));}
        return new Snapshot(id,cursor,List.copyOf(messages),List.copyOf(tasks));
    });}
    @Override public Task submit(Actor a,UUID convo,UUID key,UUID model,String text){return tx(c->{String source=null;try(var st=q(c,"SELECT source_policy FROM pf.model_binding WHERE space_id=? AND user_id=? AND model_id=?",a.spaceId(),a.userId(),model);var r=st.executeQuery()){if(r.next())source=r.getString(1);}return submit(c,a,convo,key,model,source,text);});}
    @Override public Task submitConfigured(Actor a,UUID convo,UUID key,UUID model,String source,String text){return tx(c->submit(c,a,convo,key,model,source,text));}
    private Task submit(Connection c,Actor a,UUID convo,UUID key,UUID selectedModel,String selectedSource,String text)throws SQLException{
        auth(c,a);if(text==null||text.isBlank()||text.codePointCount(0,text.length())>8192)throw new ChatFailure(400,"INVALID_CHAT_TEXT");
        String path="/conversations/"+convo+"/tasks",fingerprint=hash(selectedSource==null?selectedModel+"\n"+text:selectedModel+"\n"+selectedSource+"\n"+text);UUID existing=idempotency(c,a,key,path,fingerprint);
        if(existing!=null)return task(c,a,existing,false);
        try(var s=q(c,"SELECT pg_advisory_xact_lock(1200201)")){s.execute();}
        conversation(c,a,convo,true);
        try(var s=q(c,"SELECT count(*) FROM pf.task WHERE conversation_id=?",convo);var r=s.executeQuery()){r.next();if(r.getLong(1)>=100)throw new ChatFailure(409,"CONVERSATION_TASK_LIMIT");}
        try(var s=q(c,"SELECT count(*) FROM pf.task WHERE creator_id=? AND status='QUEUED'",a.userId());var r=s.executeQuery()){r.next();if(r.getLong(1)>=20)throw new ChatFailure(429,"QUEUE_LIMIT_EXCEEDED");}
        try(var s=q(c,"SELECT count(*),min(created_at) FROM pf.event_outbox WHERE status IN ('PENDING','PUBLISHING','REVIEW')");var r=s.executeQuery()){r.next();if(r.getLong(1)>=10000||(r.getTimestamp(2)!=null&&r.getTimestamp(2).toInstant().isBefore(Instant.now().minusSeconds(900))))throw new ChatFailure(503,"DISPATCH_UNAVAILABLE");}
        var resolved=resolve(c,a,selectedModel,selectedSource);
        UUID model=(UUID)resolved.get("modelId");String policy=(String)resolved.get("credentialSource");
        UUID credential;long revision,modelVersion,inputPrice,outputPrice;String name,pricing;
        String sql="USER".equals(policy)?
            "SELECT k.id,k.revision,m.model_name,m.version AS model_version,m.pricing_version,m.input_micros_per_million,m.output_micros_per_million FROM pf.user_model_credential u JOIN pf.credential k ON k.id=u.credential_id CROSS JOIN pf.model_definition m WHERE u.space_id=? AND u.user_id=? AND m.id=? AND k.status='ACTIVE' AND k.scope_type='USER' AND k.space_id=u.space_id AND k.owner_user_id=u.user_id AND m.status='ACTIVE' FOR SHARE OF k,u":
            "SELECT k.id,k.revision,m.model_name,m.version AS model_version,m.pricing_version,m.input_micros_per_million,m.output_micros_per_million FROM pf.model_binding b JOIN pf.credential k ON k.id=b.credential_id JOIN pf.model_definition m ON m.id=b.model_id WHERE b.space_id=? AND b.user_id=? AND b.model_id=? AND b.source_policy='PLATFORM' AND k.scope_type='PLATFORM' AND k.status='ACTIVE' AND m.status='ACTIVE' FOR SHARE OF k";
        try(var st=q(c,sql,a.spaceId(),a.userId(),model);var r=st.executeQuery()){
            if(!r.next())throw new ChatFailure(422,"CREDENTIAL_SOURCE_UNAVAILABLE");credential=r.getObject("id",UUID.class);revision=r.getLong("revision");name=r.getString("model_name");pricing=r.getString("pricing_version");modelVersion=r.getLong("model_version");inputPrice=r.getLong("input_micros_per_million");outputPrice=r.getLong("output_micros_per_million");
        }
        exec(c,"INSERT INTO pf.budget_account(space_id,user_id) VALUES(?,?) ON CONFLICT DO NOTHING",a.spaceId(),a.userId());
        long perCall=(8192L*inputPrice+1024L*outputPrice+999999)/1000000,totalCost=perCall*4;
        if(exec(c,"UPDATE pf.budget_account SET reserved_tokens=reserved_tokens+36864,reserved_micros=reserved_micros+?,version=version+1 WHERE space_id=? AND user_id=? AND reserved_tokens+consumed_tokens+36864<=token_limit AND reserved_micros+consumed_micros+?<=cost_limit_micros",totalCost,a.spaceId(),a.userId(),totalCost)!=1)throw new ChatFailure(429,"QUOTA_EXCEEDED");
        var bundle=ChatRuntime.bundle();
        exec(c,"INSERT INTO pf.runtime_bundle(id,contract_version,manifest_hash,manifest_json) VALUES(?,'HC-1.1',?,?::jsonb) ON CONFLICT DO NOTHING",BUNDLE,bundle.hash(),json(bundle));
        try(var s=q(c,"SELECT manifest_hash FROM pf.runtime_bundle WHERE id=?",BUNDLE);var r=s.executeQuery()){if(!r.next()||!bundle.hash().equals(r.getString(1)))throw new ChatFailure(409,"BUNDLE_INCOMPATIBLE");}
        UUID snapshot=Uuid7.next(),id=Uuid7.next(),userMessage=Uuid7.next(),assistant=Uuid7.next();
        var values=new TreeMap<String,Object>();values.putAll(resolved);values.put("modelId",model.toString());values.put("modelName",name);values.put("modelVersion",modelVersion);values.put("credentialId",credential.toString());values.put("credentialRevision",revision);values.put("window",8192);values.put("maxOutput",1024);values.put("thinking",false);values.put("pricingVersion",pricing);values.put("inputPrice",inputPrice);values.put("outputPrice",outputPrice);values.put("perCallMicros",perCall);values.put("bundleHash",bundle.hash());String config=json(values);

        exec(c,"INSERT INTO pf.chat_config_snapshot(id,space_id,model_id,credential_id,credential_revision,config_json,config_hash) VALUES(?,?,?,?,?,?::jsonb,?)",snapshot,a.spaceId(),model,credential,revision,config,hash(config));
        exec(c,"INSERT INTO pf.task(id,space_id,conversation_id,creator_id,config_snapshot_id,bundle_id,remaining_micros) VALUES(?,?,?,?,?,?,?)",id,a.spaceId(),convo,a.userId(),snapshot,BUNDLE,totalCost);
        exec(c,"INSERT INTO pf.message(id,space_id,conversation_id,task_id,role,blocks_json,status) VALUES(?,?,?,?,'USER',?::jsonb,'COMPLETED'),(?,?,?,?,'ASSISTANT',?::jsonb,'STREAMING')",userMessage,a.spaceId(),convo,id,json(textBlocks(userMessage,text)),assistant,a.spaceId(),convo,id,json(textBlocks(assistant,"")));
        exec(c,"INSERT INTO pf.execution_dispatch(task_id) VALUES(?)",id);outbox(c,a,id,1);
        event(c,a,convo,id,"task.accepted",1,Map.of("taskId",id.toString(),"status","QUEUED"));remember(c,a,key,path,fingerprint,id);audit(c,a,"TASK_SUBMITTED",id,key);return task(c,a,id,false);
    }
    @Override public Task task(Actor a,UUID id){return tx(c->{auth(c,a);return task(c,a,id,false);});}
    @Override public Task cancel(Actor a,UUID id,UUID key){return tx(c->{
        auth(c,a);Task t=task(c,a,id,true);conversation(c,a,t.conversationId(),true);
        String path="/tasks/"+id+"/cancel";UUID existing=idempotency(c,a,key,path,hash("cancel"));if(existing!=null)return t;
        if("QUEUED".equals(t.status())){exec(c,"UPDATE pf.task SET status='CANCELLED',cancel_requested=true,version=version+1,updated_at=now() WHERE id=?",id);exec(c,"UPDATE pf.execution_dispatch SET status='IDLE' WHERE task_id=?",id);releaseRemaining(c,a,id);exec(c,"UPDATE pf.message SET status='FAILED',version=version+1 WHERE task_id=? AND role='ASSISTANT'",id);assistantTerminalEvent(c,a,t.conversationId(),id);}
        else if("RUNNING".equals(t.status()))exec(c,"UPDATE pf.task SET cancel_requested=true,version=version+1 WHERE id=?",id);
        remember(c,a,key,path,hash("cancel"),id);Task now=task(c,a,id,false);event(c,a,t.conversationId(),id,"task.state_changed",now.version(),Map.of("taskId",id.toString(),"status",now.status(),"cancelRequested",now.cancelRequested()));return now;
    });}
    @Override public List<Event> events(Actor a,UUID id,long after){return tx(c->{
        auth(c,a);conversation(c,a,id,false);
        long current;try(var s=q(c,"SELECT next_seq FROM pf.conversation WHERE id=?",id);var r=s.executeQuery()){r.next();current=r.getLong(1);if(after<0||after>current)throw new ChatFailure(409,"INVALID_CURSOR");}
        try(var s=q(c,"SELECT min(seq) FROM pf.platform_event WHERE conversation_id=? AND created_at>=now()-interval '30 days'",id);var r=s.executeQuery()){
            r.next();Long first=r.getObject(1,Long.class);if(after<current&&(first==null||after<first-1))throw new ChatFailure(410,"CURSOR_EXPIRED");
        }
        List<Event> result=new ArrayList<>();try(var s=q(c,"SELECT * FROM pf.platform_event WHERE space_id=? AND conversation_id=? AND seq>? AND created_at>=now()-interval '30 days' ORDER BY seq LIMIT 256",a.spaceId(),id,after);var r=s.executeQuery()){
            while(r.next())result.add(new Event(r.getObject("id",UUID.class),a.spaceId(),id,r.getObject("task_id",UUID.class),r.getLong("seq"),r.getString("type"),r.getLong("entity_version"),r.getTimestamp("created_at").toInstant(),object(r.getString("payload_json"))));
        }return List.copyOf(result);
    });}
    @Override public List<Usage> usage(Actor a,UUID id){return tx(c->{auth(c,a);task(c,a,id,false);List<Usage> result=new ArrayList<>();try(var s=q(c,"SELECT * FROM pf.model_call WHERE space_id=? AND task_id=? ORDER BY ordinal",a.spaceId(),id);var r=s.executeQuery()){while(r.next())result.add(new Usage(r.getObject("id",UUID.class),r.getString("status"),r.getObject("input_tokens",Long.class),r.getObject("output_tokens",Long.class),r.getObject("cost_micros",Long.class),r.getBoolean("estimated")));}return List.copyOf(result);});}
    @Override public List<Publish> publications(UUID relay){return tx(c->{
        List<Publish> result=new ArrayList<>();
        try(var s=q(c,"SELECT * FROM pf.event_outbox WHERE (status='PENDING' AND available_at<=now()) OR (status='PUBLISHING' AND publish_lease_until<now()) ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 100");var r=s.executeQuery()){
            while(r.next()){UUID id=r.getObject("id",UUID.class);exec(c,"UPDATE pf.event_outbox SET status='PUBLISHING',publish_owner=?,publish_lease_until=now()+interval '30 seconds',attempts=attempts+1 WHERE id=?",relay,id);result.add(new Publish(id,new Envelope(1,r.getObject("event_id",UUID.class),r.getObject("space_id",UUID.class),r.getObject("aggregate_id",UUID.class),r.getLong("generation"),"execution.ready",r.getTimestamp("created_at").toInstant(),null,r.getObject("aggregate_id",UUID.class))));}
        }return List.copyOf(result);
    });}
    @Override public void published(UUID relay,Publish item,boolean success){tx(c->{
        if(success)exec(c,"UPDATE pf.event_outbox SET status='PUBLISHED',publish_lease_until=NULL,last_error=NULL WHERE id=? AND publish_owner=? AND status='PUBLISHING'",item.id(),relay);
        else exec(c,"UPDATE pf.event_outbox SET status=CASE WHEN attempts>=10 THEN 'REVIEW' ELSE 'PENDING' END,available_at=now()+make_interval(secs=>CASE WHEN attempts=1 THEN 1 WHEN attempts=2 THEN 5 WHEN attempts=3 THEN 30 WHEN attempts=4 THEN 120 ELSE 600 END),publish_lease_until=NULL,last_error='PUBLISH_NOT_CONFIRMED' WHERE id=? AND publish_owner=? AND status='PUBLISHING'",item.id(),relay);return null;
    });}
    @Override public void wakeDeferred(){tx(c->{
        try(var s=q(c,"SELECT d.task_id,d.generation,t.space_id,t.creator_id FROM pf.execution_dispatch d JOIN pf.task t ON t.id=d.task_id WHERE d.status='DEFERRED' AND d.available_at<=now() AND t.status='QUEUED' AND NOT EXISTS(SELECT 1 FROM pf.execution_slot WHERE task_id IS NOT NULL) AND NOT EXISTS(SELECT 1 FROM pf.task prior WHERE prior.conversation_id=t.conversation_id AND prior.status IN ('QUEUED','RUNNING') AND (prior.created_at,prior.id)<(t.created_at,t.id)) ORDER BY t.created_at,t.id FOR UPDATE OF d SKIP LOCKED LIMIT 1");var r=s.executeQuery()){
            while(r.next()){UUID id=r.getObject(1,UUID.class);long generation=r.getLong(2)+1;exec(c,"UPDATE pf.execution_dispatch SET status='READY',generation=?,available_at=now() WHERE task_id=?",generation,id);outbox(c,new Actor(r.getObject(4,UUID.class),r.getObject(3,UUID.class)),id,generation);}
        }return null;
    });}
    @Override public Lease claim(Envelope e,UUID owner){return tx(c->{
        if(e.schemaVersion()!=1||!"execution.ready".equals(e.kind())||!e.aggregateId().equals(e.payloadRef())||e.generation()<1)throw new ChatFailure(400,"INVALID_DISPATCH_MESSAGE");
        try(var s=q(c,"SELECT 1 FROM pf.consumer_inbox WHERE consumer_name='execution' AND event_id=?",e.eventId());var r=s.executeQuery()){if(r.next())return null;}
        boolean busy;long fence;
        try(var s=q(c,"SELECT task_id,fencing_token FROM pf.execution_slot WHERE id=1 FOR UPDATE");var r=s.executeQuery()){r.next();busy=r.getObject(1)!=null;fence=r.getLong(2)+1;}
        UUID actor,convo,model,credential,assistant;long revision;
        try(var s=q(c,"SELECT t.*,d.generation,d.status AS dispatch_status,s.model_id,s.credential_id,s.credential_revision FROM pf.task t JOIN pf.execution_dispatch d ON d.task_id=t.id JOIN pf.chat_config_snapshot s ON s.id=t.config_snapshot_id WHERE t.space_id=? AND t.id=? FOR UPDATE OF t,d",e.spaceId(),e.aggregateId());var r=s.executeQuery()){
            if(!r.next())throw new ChatFailure(400,"UNKNOWN_DISPATCH_TASK");
            actor=r.getObject("creator_id",UUID.class);convo=r.getObject("conversation_id",UUID.class);model=r.getObject("model_id",UUID.class);credential=r.getObject("credential_id",UUID.class);revision=r.getLong("credential_revision");
            if(e.generation()!=r.getLong("generation")||!"READY".equals(r.getString("dispatch_status"))||!"QUEUED".equals(r.getString("status"))){inbox(c,e,"IGNORED");return null;}
        }
        Actor a=new Actor(actor,e.spaceId());
        try { auth(c,a); } catch(ChatFailure denied) {
            if(denied.status()!=404)throw denied;
            conversation(c,a,convo,true);
            exec(c,"UPDATE pf.task SET status='FAILED',reason_code='AUTHORIZATION_REVOKED',version=version+1 WHERE id=?",e.aggregateId());
            exec(c,"UPDATE pf.message SET status='FAILED',version=version+1 WHERE task_id=? AND role='ASSISTANT'",e.aggregateId());
            exec(c,"UPDATE pf.execution_dispatch SET status='IDLE' WHERE task_id=?",e.aggregateId());
            releaseRemaining(c,a,e.aggregateId());inbox(c,e,"IGNORED");
            assistantTerminalEvent(c,a,convo,e.aggregateId());
            event(c,a,convo,e.aggregateId(),"task.state_changed",2,Map.of("taskId",e.aggregateId().toString(),"status","FAILED","reasonCode","AUTHORIZATION_REVOKED"));return null;
        }
        conversation(c,a,convo,true);
        try(var s=q(c,"SELECT 1 FROM pf.task t WHERE t.conversation_id=? AND t.id<>? AND t.status IN ('QUEUED','RUNNING') AND (t.created_at,t.id)<(SELECT created_at,id FROM pf.task WHERE id=?) LIMIT 1",convo,e.aggregateId(),e.aggregateId());var r=s.executeQuery()){busy|=r.next();}
        if(busy){exec(c,"UPDATE pf.execution_dispatch SET status='DEFERRED',available_at=now()+interval '1 second' WHERE task_id=?",e.aggregateId());inbox(c,e,"DEFERRED");return null;}
        UUID attempt=Uuid7.next();
        exec(c,"UPDATE pf.execution_slot SET task_id=?,owner_id=?,fencing_token=?,lease_until=now()+interval '60 seconds' WHERE id=1",e.aggregateId(),owner,fence);
        exec(c,"UPDATE pf.execution_dispatch SET status='CLAIMED',lease_owner=?,fencing_token=?,lease_until=now()+interval '60 seconds' WHERE task_id=?",owner,fence,e.aggregateId());
        exec(c,"INSERT INTO pf.attempt(id,space_id,task_id,ordinal,owner_id,fencing_token) SELECT ?,space_id,id,(SELECT count(*)+1 FROM pf.attempt WHERE task_id=?),?,? FROM pf.task WHERE id=?",attempt,e.aggregateId(),owner,fence,e.aggregateId());
        exec(c,"UPDATE pf.task SET status='RUNNING',version=version+1,updated_at=now() WHERE id=?",e.aggregateId());inbox(c,e,"CLAIMED");
        try(var s=q(c,"SELECT id FROM pf.message WHERE task_id=? AND role='ASSISTANT'",e.aggregateId());var r=s.executeQuery()){r.next();assistant=r.getObject(1,UUID.class);}
        event(c,a,convo,e.aggregateId(),"task.state_changed",2,Map.of("taskId",e.aggregateId().toString(),"status","RUNNING"));
        event(c,a,convo,e.aggregateId(),"message.started",1,Map.of("messageId",assistant.toString(),"blockId",assistant.toString(),"role","ASSISTANT"));
        return new Lease(e.aggregateId(),e.spaceId(),actor,convo,attempt,owner,fence,model,credential,revision,assistant);
    });}
    private static void inbox(Connection c,Envelope e,String disposition)throws SQLException{exec(c,"INSERT INTO pf.consumer_inbox(consumer_name,event_id,space_id,aggregate_id,generation,disposition) VALUES('execution',?,?,?,?,?) ON CONFLICT DO NOTHING",e.eventId(),e.spaceId(),e.aggregateId(),e.generation(),disposition);}
    @Override public void quarantine(String reason){tx(c->{exec(c,"INSERT INTO pf.dispatch_quarantine(id,reason_code) VALUES(?,?)",Uuid7.next(),reason);return null;});}
    private static void owned(Connection c,Lease l,boolean cancellationAllowed)throws SQLException{
        try(var s=q(c,"SELECT 1 FROM pf.execution_slot WHERE id=1 AND task_id=? AND owner_id=? AND fencing_token=? AND lease_until>now() FOR UPDATE",l.taskId(),l.ownerId(),l.fence());var r=s.executeQuery()){if(!r.next())throw new ChatFailure(409,"EXECUTION_FENCE_LOST");}
        try(var s=q(c,"SELECT 1 FROM pf.attempt WHERE id=? AND task_id=? AND owner_id=? AND fencing_token=? AND status='RUNNING'",l.attemptId(),l.taskId(),l.ownerId(),l.fence());var r=s.executeQuery()){if(!r.next())throw new ChatFailure(409,"EXECUTION_FENCE_LOST");}
        Actor a=new Actor(l.userId(),l.spaceId());Task t=task(c,a,l.taskId(),true);conversation(c,a,l.conversationId(),true);
        if(!"RUNNING".equals(t.status()))throw new ChatFailure(409,"EXECUTION_FENCE_LOST");
        if(!cancellationAllowed){auth(c,a);if(t.cancelRequested())throw new ChatFailure(409,"TASK_CANCEL_REQUESTED");}
    }
    @Override public void check(Lease l){tx(c->{owned(c,l,false);authorizeCredential(c,l);activeModel(c,l);return null;});}
    @Override public void heartbeat(Lease l){tx(c->{owned(c,l,false);authorizeCredential(c,l);activeModel(c,l);exec(c,"UPDATE pf.execution_slot SET lease_until=now()+interval '60 seconds' WHERE id=1");exec(c,"UPDATE pf.execution_dispatch SET lease_until=now()+interval '60 seconds' WHERE task_id=?",l.taskId());return null;});}
    @Override public List<HarnessData.Message> history(Lease l){return tx(c->{
        owned(c,l,false);List<HarnessData.Message> messages=new ArrayList<>();messages.add(HarnessData.Message.text(HarnessData.Role.SYSTEM,"You are a helpful assistant. Answer ordinary text questions. No tools or filesystem access are available."));
        try(var s=q(c,"SELECT m.role,m.blocks_json FROM pf.message m JOIN pf.task t ON t.id=m.task_id WHERE m.space_id=? AND m.conversation_id=? AND ((t.status='SUCCEEDED' AND m.status='COMPLETED') OR (t.id=? AND m.role='USER')) ORDER BY m.created_at,m.id",l.spaceId(),l.conversationId(),l.taskId());var r=s.executeQuery()){
            long estimate=512;while(r.next()){String text=String.valueOf(blocks(r.getString(2)).getFirst().get("text"));estimate+=text.getBytes(StandardCharsets.UTF_8).length+32;if(estimate>6144)throw new ChatFailure(422,"CONTEXT_REQUIRED_TOO_LARGE");messages.add(HarnessData.Message.text(HarnessData.Role.valueOf(r.getString(1)),text));}
        }return List.copyOf(messages);
    });}
    @Override public void delta(Lease l,String text){if(text.isEmpty())return;tx(c->{
        owned(c,l,false);authorizeCredential(c,l);activeModel(c,l);String prior;long version;
        try(var s=q(c,"SELECT blocks_json,version FROM pf.message WHERE id=?",l.assistantId());var r=s.executeQuery()){r.next();prior=String.valueOf(blocks(r.getString(1)).getFirst().get("text"));version=r.getLong(2)+1;}
        if(prior.getBytes(StandardCharsets.UTF_8).length+text.getBytes(StandardCharsets.UTF_8).length>65536)throw new ChatFailure(422,"MODEL_OUTPUT_LIMIT");
        exec(c,"UPDATE pf.message SET blocks_json=?::jsonb,version=?,updated_at=now() WHERE id=?",json(textBlocks(l.assistantId(),prior+text)),version,l.assistantId());
        event(c,new Actor(l.userId(),l.spaceId()),l.conversationId(),l.taskId(),"message.delta",version,Map.of("messageId",l.assistantId().toString(),"blockId",l.assistantId().toString(),"offset",prior.codePointCount(0,prior.length()),"text",text));return null;
    });}
    @Override public Call beginCall(Lease l,boolean real){return tx(c->{
        owned(c,l,false);int ordinal;
        try(var s=q(c,"SELECT count(*),min(created_at) FROM pf.model_call WHERE task_id=?",l.taskId());var r=s.executeQuery()){r.next();ordinal=r.getInt(1)+1;if(ordinal>4||(r.getTimestamp(2)!=null&&r.getTimestamp(2).toInstant().isBefore(Instant.now().minusSeconds(120))))throw new ChatFailure(429,"MODEL_CALL_LIMIT");}
        long allowance=callAllowance(c,l);
        if(exec(c,"UPDATE pf.task SET remaining_tokens=remaining_tokens-9216,remaining_micros=remaining_micros-? WHERE id=? AND remaining_tokens>=9216 AND remaining_micros>=?",allowance,l.taskId(),allowance)!=1)throw new ChatFailure(429,"QUOTA_EXCEEDED");
        UUID id=Uuid7.next();String price=(String)frozen(c,l).get("pricingVersion");
        exec(c,"INSERT INTO pf.model_call(id,space_id,task_id,attempt_id,ordinal,price_version,status) VALUES(?,?,?,?,?,?,'PREPARED')",id,l.spaceId(),l.taskId(),l.attemptId(),ordinal,price);
        exec(c,"INSERT INTO pf.budget_reservation(call_id,task_id,amount_micros) VALUES(?,?,?)",id,l.taskId(),allowance);return new Call(id,ordinal,price);
    });}
    @Override public void startCall(Lease l,Call call,boolean real){tx(c->{owned(c,l,false);
        authorizeCredential(c,l);activeModel(c,l);
        long allowance=callAllowance(c,l);
        if(real&&exec(c,"UPDATE pf.acceptance_model_meter SET physical_calls=physical_calls+1,reserved_micros=reserved_micros+? WHERE id=1 AND physical_calls<20 AND reserved_micros+consumed_micros+?<=1000000",allowance,allowance)!=1)throw new ChatFailure(429,"ACCEPTANCE_MODEL_LIMIT");
        if(exec(c,"UPDATE pf.model_call SET status='STARTED' WHERE id=? AND task_id=? AND status='PREPARED'",call.id(),l.taskId())!=1)throw new ChatFailure(409,"CALL_ALREADY_STARTED");return null;
    });}
    @Override public void settle(Lease l,Call call,HarnessData.Round round,String requestId,String error,boolean real){tx(c->{
        owned(c,l,true);long allowance=callAllowance(c,l);var prices=frozen(c,l);String state;
        try(var s=q(c,"SELECT r.state FROM pf.budget_reservation r JOIN pf.model_call m ON m.id=r.call_id WHERE r.call_id=? AND m.task_id=? AND m.attempt_id=? AND m.space_id=? FOR UPDATE OF r",call.id(),l.taskId(),l.attemptId(),l.spaceId());var r=s.executeQuery()){if(!r.next())throw new ChatFailure(409,"CALL_NOT_FOUND");state=r.getString(1);}
        if(!"RESERVED".equals(state))return null;
        if(round==null){
            boolean unbilled=Set.of("PROVIDER_RATE_LIMITED","PROVIDER_AUTH_REJECTED","CALL_NOT_SENT").contains(error==null?"":error);
            String outcome=unbilled||"PROVIDER_SERVER_REJECTED".equals(error)?"FAILED":"PROVIDER_USAGE_MISSING".equals(error)?"COMPLETED":"UNKNOWN";
            exec(c,"UPDATE pf.model_call SET status=?,error_code=?,ended_at=now() WHERE id=?",outcome,error,call.id());
            exec(c,"UPDATE pf.budget_reservation SET state=? WHERE call_id=?",unbilled?"RELEASED":"RECONCILING",call.id());
            if(unbilled){exec(c,"UPDATE pf.budget_account SET reserved_tokens=reserved_tokens-9216,reserved_micros=reserved_micros-?,version=version+1 WHERE space_id=? AND user_id=?",allowance,l.spaceId(),l.userId());if(real)exec(c,"UPDATE pf.acceptance_model_meter SET reserved_micros=reserved_micros-? WHERE id=1",allowance);}return null;
        }
        if(round.inputTokens()<0||round.inputTokens()>8192||round.outputTokens()<0||round.outputTokens()>1024)throw new ChatFailure(409,"PROVIDER_USAGE_EXCEEDS_RESERVATION");
        long tokens=round.inputTokens()+round.outputTokens(),cost=(round.inputTokens()*((Number)prices.getOrDefault("inputPrice",2000000L)).longValue()+round.outputTokens()*((Number)prices.getOrDefault("outputPrice",8000000L)).longValue()+999999)/1000000;
        if(tokens>9216||cost>allowance)throw new ChatFailure(409,"PROVIDER_USAGE_EXCEEDS_RESERVATION");
        exec(c,"UPDATE pf.model_call SET status='COMPLETED',input_tokens=?,output_tokens=?,cost_micros=?,estimated=true,provider_request_id=?,ended_at=now() WHERE id=?",round.inputTokens(),round.outputTokens(),cost,requestId,call.id());
        exec(c,"UPDATE pf.budget_reservation SET state='SETTLED' WHERE call_id=?",call.id());
        exec(c,"UPDATE pf.budget_account SET reserved_tokens=reserved_tokens-9216,reserved_micros=reserved_micros-?,consumed_tokens=consumed_tokens+?,consumed_micros=consumed_micros+?,version=version+1 WHERE space_id=? AND user_id=?",allowance,tokens,cost,l.spaceId(),l.userId());
        if(real)exec(c,"UPDATE pf.acceptance_model_meter SET reserved_micros=reserved_micros-?,consumed_micros=consumed_micros+? WHERE id=1",allowance,cost);
        event(c,new Actor(l.userId(),l.spaceId()),l.conversationId(),l.taskId(),"usage.updated",call.ordinal(),Map.of("callId",call.id().toString(),"inputTokens",round.inputTokens(),"outputTokens",round.outputTokens(),"costMicros",cost,"estimated",true));return null;
    });}
    @Override public Credential credential(Lease l,UUID call){return tx(c->{
        owned(c,l,false);
        authorizeCredential(c,l);
        try(var s=q(c,"SELECT 1 FROM pf.model_call WHERE id=? AND task_id=? AND attempt_id=? AND space_id=? AND status='PREPARED'",call,l.taskId(),l.attemptId(),l.spaceId());var r=s.executeQuery()){if(!r.next())throw new ChatFailure(409,"CALL_NOT_FOUND");}
        try(var s=q(c,"SELECT * FROM pf.credential WHERE id=? AND status='ACTIVE' AND revision=?",l.credentialId(),l.credentialRevision());var r=s.executeQuery()){
            if(!r.next())throw new ChatFailure(403,"CREDENTIAL_REVOKED");String scope=r.getString("scope_type");UUID space=r.getObject("space_id",UUID.class),owner=r.getObject("owner_user_id",UUID.class);
            if("USER".equals(scope)&&(!l.spaceId().equals(space)||!l.userId().equals(owner)))throw new ChatFailure(403,"CREDENTIAL_SCOPE_REJECTED");
            exec(c,"INSERT INTO pf.credential_access_audit(id,credential_id,call_id,result) VALUES(?,?,?,'AUTHORIZED')",Uuid7.next(),l.credentialId(),call);
            return new Credential(l.credentialId(),scope,space,owner,r.getBytes("ciphertext"),r.getBytes("nonce"),r.getInt("key_version"),r.getLong("revision"));
        }
    });}
    private static void authorizeCredential(Connection c,Lease l)throws SQLException{
        try(var s=q(c,"SELECT pf.authorize_chat_credential(?,?,?,?)",l.credentialId(),l.credentialRevision(),l.spaceId(),l.userId());var r=s.executeQuery()){
            if(!r.next()||!r.getBoolean(1))throw new ChatFailure(403,"CREDENTIAL_REVOKED");
        }
    }
    private static void releaseRemaining(Connection c,Actor a,UUID task)throws SQLException{
        exec(c,"UPDATE pf.budget_account b SET reserved_tokens=b.reserved_tokens-t.remaining_tokens,reserved_micros=b.reserved_micros-t.remaining_micros,version=b.version+1 FROM pf.task t WHERE t.id=? AND b.space_id=? AND b.user_id=?",task,a.spaceId(),a.userId());
        exec(c,"UPDATE pf.task SET remaining_tokens=0,remaining_micros=0 WHERE id=?",task);
    }
    private static void assistantTerminalEvent(Connection c,Actor a,UUID conversation,UUID task)throws SQLException{
        try(var s=q(c,"SELECT id,status,version FROM pf.message WHERE space_id=? AND task_id=? AND role='ASSISTANT'",a.spaceId(),task);var r=s.executeQuery()){
            if(r.next())event(c,a,conversation,task,"message.completed",r.getLong(3),Map.of("messageId",r.getObject(1,UUID.class).toString(),"status",r.getString(2)));
        }
    }
    private static void releasePrepared(Connection c,Actor a,UUID task)throws SQLException{
        exec(c,"WITH released AS (UPDATE pf.budget_reservation r SET state='RELEASED' FROM pf.model_call m WHERE r.call_id=m.id AND m.task_id=? AND m.status='PREPARED' AND r.state='RESERVED' RETURNING r.tokens,r.amount_micros) UPDATE pf.budget_account SET reserved_tokens=reserved_tokens-(SELECT coalesce(sum(tokens),0) FROM released),reserved_micros=reserved_micros-(SELECT coalesce(sum(amount_micros),0) FROM released),version=version+1 WHERE space_id=? AND user_id=?",task,a.spaceId(),a.userId());
        exec(c,"UPDATE pf.model_call SET status='FAILED',error_code='CALL_NOT_SENT',ended_at=now() WHERE task_id=? AND status='PREPARED'",task);
    }
    @Override public void finish(Lease l,String status,String reason){tx(c->{
        owned(c,l,true);if(!Set.of("SUCCEEDED","FAILED","CANCELLED","BLOCKED").contains(status))throw new IllegalArgumentException("Invalid terminal outcome");
        releasePrepared(c,new Actor(l.userId(),l.spaceId()),l.taskId());
        // An open call cannot disappear when cancellation/provider failure interrupts its result.
        exec(c,"UPDATE pf.budget_reservation r SET state='RECONCILING' FROM pf.model_call m WHERE r.call_id=m.id AND m.task_id=? AND r.state='RESERVED'",l.taskId());
        exec(c,"UPDATE pf.model_call SET status='UNKNOWN',error_code=coalesce(error_code,?),ended_at=now() WHERE task_id=? AND status='STARTED'",reason,l.taskId());
        releaseRemaining(c,new Actor(l.userId(),l.spaceId()),l.taskId());
        exec(c,"UPDATE pf.task SET status=?,reason_code=?,version=version+1,updated_at=now() WHERE id=?",status,reason,l.taskId());
        String text;long messageVersion;
        try(var s=q(c,"SELECT blocks_json,version FROM pf.message WHERE id=?",l.assistantId());var r=s.executeQuery()){r.next();text=String.valueOf(blocks(r.getString(1)).getFirst().get("text"));messageVersion=r.getLong(2)+1;}
        String messageStatus="SUCCEEDED".equals(status)?"COMPLETED":text.isEmpty()?"FAILED":"PARTIAL";
        exec(c,"UPDATE pf.message SET status=?,version=?,updated_at=now() WHERE id=?",messageStatus,messageVersion,l.assistantId());
        exec(c,"UPDATE pf.attempt SET status=?,reason_code=?,ended_at=now() WHERE id=?",status,reason,l.attemptId());exec(c,"UPDATE pf.execution_dispatch SET status='IDLE',lease_until=NULL WHERE task_id=?",l.taskId());
        exec(c,"UPDATE pf.execution_slot SET task_id=NULL,owner_id=NULL,lease_until=NULL WHERE id=1");
        Actor a=new Actor(l.userId(),l.spaceId());event(c,a,l.conversationId(),l.taskId(),"message.completed",messageVersion,Map.of("messageId",l.assistantId().toString(),"status",messageStatus));Task task=task(c,a,l.taskId(),false);
        var payload=new LinkedHashMap<String,Object>();payload.put("taskId",l.taskId().toString());payload.put("status",status);payload.put("reasonCode",reason);event(c,a,l.conversationId(),l.taskId(),"task.state_changed",task.version(),payload);return null;
    });}
    @Override public void recoverStopped(UUID owner){tx(c->{
        UUID task;long fence;
        try(var s=q(c,"SELECT task_id,fencing_token,lease_until FROM pf.execution_slot WHERE owner_id=? FOR UPDATE",owner);var r=s.executeQuery()){if(!r.next()||r.getObject(1)==null)return null;if(r.getTimestamp(3).toInstant().isAfter(Instant.now()))throw new ChatFailure(409,"OWNER_LEASE_NOT_EXPIRED");task=r.getObject(1,UUID.class);fence=r.getLong(2);}
        // The offline operator must first attest the old worker process/container is stopped.
        UUID user,space,convo,attempt,assistant,model,credential;long revision;
        try(var s=q(c,"SELECT t.*,s.model_id,s.credential_id,s.credential_revision,a.id AS attempt_id,m.id AS assistant_id FROM pf.task t JOIN pf.chat_config_snapshot s ON s.id=t.config_snapshot_id JOIN pf.attempt a ON a.task_id=t.id AND a.fencing_token=? JOIN pf.message m ON m.task_id=t.id AND m.role='ASSISTANT' WHERE t.id=?",fence,task);var r=s.executeQuery()){
            r.next();user=r.getObject("creator_id",UUID.class);space=r.getObject("space_id",UUID.class);convo=r.getObject("conversation_id",UUID.class);attempt=r.getObject("attempt_id",UUID.class);assistant=r.getObject("assistant_id",UUID.class);model=r.getObject("model_id",UUID.class);credential=r.getObject("credential_id",UUID.class);revision=r.getLong("credential_revision");
        }
        // Temporarily fenced, only within this locked transaction, for normal failure finalization.
        exec(c,"UPDATE pf.execution_slot SET lease_until=now()+interval '5 seconds' WHERE id=1");
        Lease l=new Lease(task,space,user,convo,attempt,owner,fence,model,credential,revision,assistant);
        owned(c,l,true);releasePrepared(c,new Actor(user,space),task);exec(c,"UPDATE pf.model_call SET status='UNKNOWN',error_code='WORKER_INTERRUPTED',ended_at=now() WHERE task_id=? AND status='STARTED'",task);exec(c,"UPDATE pf.budget_reservation SET state='RECONCILING' WHERE task_id=? AND state='RESERVED'",task);releaseRemaining(c,new Actor(user,space),task);
        exec(c,"UPDATE pf.task SET status='FAILED',reason_code='WORKER_INTERRUPTED',version=version+1,updated_at=now() WHERE id=?",task);exec(c,"UPDATE pf.attempt SET status='INTERRUPTED',reason_code='WORKER_INTERRUPTED',ended_at=now() WHERE id=?",attempt);
        exec(c,"UPDATE pf.message SET status=CASE WHEN blocks_json->0->>'text'='' THEN 'FAILED' ELSE 'PARTIAL' END,version=version+1 WHERE id=?",assistant);exec(c,"UPDATE pf.execution_dispatch SET status='IDLE',lease_until=NULL WHERE task_id=?",task);exec(c,"UPDATE pf.execution_slot SET task_id=NULL,owner_id=NULL,lease_until=NULL,fencing_token=fencing_token+1 WHERE id=1");
        assistantTerminalEvent(c,new Actor(user,space),convo,task);
        event(c,new Actor(user,space),convo,task,"task.state_changed",task(c,new Actor(user,space),task,false).version(),Map.of("taskId",task.toString(),"status","FAILED","reasonCode","WORKER_INTERRUPTED"));return null;
    });}
    @Override public UUID importCredential(Credential k){return tx(c->{exec(c,"INSERT INTO pf.credential(id,scope_type,space_id,owner_user_id,ciphertext,nonce,key_version,revision) VALUES(?,?,?,?,?,?,?,?)",k.id(),k.scope(),k.spaceId(),k.ownerId(),k.ciphertext(),k.nonce(),k.keyVersion(),k.revision());return k.id();});}
    @Override public void revokeCredential(UUID id){tx(c->{
        if(exec(c,"UPDATE pf.credential SET status='REVOKED',revision=revision+1 WHERE id=? AND status='ACTIVE'",id)==0){
            try(var s=q(c,"SELECT id FROM pf.credential WHERE id=?",id);var r=s.executeQuery()){if(!r.next())throw new ChatFailure(404,"RESOURCE_NOT_FOUND");}
        }
        return null;
    });}
    @Override public void bind(UUID model,Actor a,UUID credential,String source){tx(c->{auth(c,a);
        try(var s=q(c,"SELECT scope_type,space_id,owner_user_id FROM pf.credential WHERE id=? AND status='ACTIVE'",credential);var r=s.executeQuery()){if(!r.next()||!source.equals(r.getString(1))||("USER".equals(source)&&(!a.spaceId().equals(r.getObject(2,UUID.class))||!a.userId().equals(r.getObject(3,UUID.class)))))throw new ChatFailure(403,"CREDENTIAL_SCOPE_REJECTED");}
        exec(c,"INSERT INTO pf.model_definition(id,model_name,endpoint_alias) VALUES(?,'deepseek-flash','DEEPSEEK') ON CONFLICT DO NOTHING",model);
        exec(c,"INSERT INTO pf.model_binding(space_id,user_id,model_id,credential_id,source_policy) VALUES(?,?,?,?,?) ON CONFLICT(space_id,user_id,model_id) DO UPDATE SET credential_id=EXCLUDED.credential_id,source_policy=EXCLUDED.source_policy,version=pf.model_binding.version+1",a.spaceId(),a.userId(),model,credential,source);
        exec(c,"INSERT INTO pf.budget_account(space_id,user_id) VALUES(?,?) ON CONFLICT DO NOTHING",a.spaceId(),a.userId());
        if("USER".equals(source))exec(c,"INSERT INTO pf.user_model_credential(space_id,user_id,provider,credential_id,version) VALUES(?,?,'DEEPSEEK',?,1) ON CONFLICT(space_id,user_id,provider) DO UPDATE SET credential_id=excluded.credential_id,version=pf.user_model_credential.version+1",a.spaceId(),a.userId(),credential);
        return null;
    });}
    private static void audit(Connection c,Actor a,String kind,UUID resource,UUID trace)throws SQLException{
        exec(c,"INSERT INTO pf.chat_audit(id,space_id,user_id,kind,resource_id,trace_id) VALUES(?,?,?,?,?,?)",Uuid7.next(),a.spaceId(),a.userId(),kind,resource,trace);
    }
    @Override public First first(Actor a,UUID key,String title,UUID model,String source,String text){return tx(c->{
        auth(c,a);String name=title==null?"新对话":title;if(name.isBlank()||name.codePointCount(0,name.length())>120)throw new ChatFailure(400,"INVALID_TITLE");
        String fingerprint=hash(json(Arrays.asList(name,model,source,text)));UUID existing=idempotency(c,a,key,"/conversations",fingerprint);
        if(existing!=null){Conversation convo;try(var st=q(c,"SELECT * FROM pf.conversation WHERE space_id=? AND id=?",a.spaceId(),existing);var r=st.executeQuery()){if(!r.next())throw new ChatFailure(404,"RESOURCE_NOT_FOUND");convo=readConversation(r);}try(var st=q(c,"SELECT * FROM pf.task WHERE conversation_id=? ORDER BY created_at,id LIMIT 1",existing);var r=st.executeQuery()){if(!r.next())throw new ChatFailure(409,"IDEMPOTENCY_CONFLICT");return new First(convo,readTask(r));}}
        UUID id=Uuid7.next();exec(c,"INSERT INTO pf.conversation(id,space_id,creator_id,title) VALUES(?,?,?,?)",id,a.spaceId(),a.userId(),name);
        Task task=submit(c,a,id,key,model,source,text);remember(c,a,key,"/conversations",fingerprint,id);
        try(var st=q(c,"SELECT * FROM pf.conversation WHERE id=?",id);var r=st.executeQuery()){r.next();return new First(readConversation(r),task);}
    });}
    private static Map<String,Object> preferences(Connection c,Actor a)throws SQLException{
        long version=0;try(var st=q(c,"SELECT version FROM pf.user_preference_revision WHERE user_id=?",a.userId());var r=st.executeQuery()){if(r.next())version=r.getLong(1);}
        Map<String,Object> result=new LinkedHashMap<>();result.put("version",version);result.put("defaultModelId",null);result.put("defaultCredentialSource","PLATFORM");
        try(var st=q(c,"SELECT key,value_json FROM pf.user_preference WHERE user_id=?",a.userId());var r=st.executeQuery()){while(r.next())result.put(r.getString(1),JSON.readValue(r.getString(2),Object.class));}return result;
    }
    @Override public Map<String,Object> preferences(Actor a){return tx(c->{auth(c,a);return preferences(c,a);});}
    @Override public Map<String,Object> preferences(Actor a,long expected,Map<String,Object> patch){return tx(c->{
        auth(c,a);if(!Set.of("defaultModelId","defaultCredentialSource").containsAll(patch.keySet()))throw new ChatFailure(422,"UNSUPPORTED_PREFERENCE");
        exec(c,"INSERT INTO pf.user_preference_revision(user_id) VALUES(?) ON CONFLICT DO NOTHING",a.userId());
        if(exec(c,"UPDATE pf.user_preference_revision SET version=version+1 WHERE user_id=? AND version=?",a.userId(),expected)!=1)throw new ChatFailure(412,"PREFERENCE_VERSION_CONFLICT");
        for(var entry:patch.entrySet()){
            Object value=entry.getValue();if("defaultModelId".equals(entry.getKey())){if(value!=null){UUID model;try{model=UUID.fromString((String)value);}catch(Exception invalid){throw new ChatFailure(422,"INVALID_MODEL");}validateModel(c,model);}}
            else if(!Set.of("PLATFORM","USER").contains(value==null?"":value))throw new ChatFailure(422,"INVALID_CREDENTIAL_SOURCE");
            exec(c,"INSERT INTO pf.user_preference(user_id,key,value_json,version) VALUES(?,?,?::jsonb,?) ON CONFLICT(user_id,key) DO UPDATE SET value_json=excluded.value_json,version=excluded.version",a.userId(),entry.getKey(),json(value),expected+1);
        }audit(c,a,"PREFERENCES_UPDATED",a.userId(),Uuid7.next());return preferences(c,a);
    });}
    private static void validateModel(Connection c,UUID model)throws SQLException{
        try(var st=q(c,"SELECT 1 FROM pf.model_definition WHERE id=? AND status='ACTIVE' AND model_name IN ('deepseek-flash','deepseek-v4-pro')",model);var r=st.executeQuery()){if(!r.next())throw new ChatFailure(422,"MODEL_CAPABILITY_MISMATCH");}
    }
    private static Map<String,Object> resolve(Connection c,Actor a,UUID selected,String source)throws SQLException{
        var prefs=preferences(c,a);UUID model=selected;
        if(model==null&&prefs.get("defaultModelId")!=null)model=UUID.fromString((String)prefs.get("defaultModelId"));
        if(model==null)try(var st=q(c,"SELECT id FROM pf.model_definition WHERE model_name='deepseek-flash' AND status='ACTIVE' ORDER BY id LIMIT 1");var r=st.executeQuery()){if(!r.next())throw new ChatFailure(503,"MODEL_UNAVAILABLE");model=r.getObject(1,UUID.class);}
        validateModel(c,model);String policy=source==null?(String)prefs.get("defaultCredentialSource"):source;
        if(!Set.of("PLATFORM","USER").contains(policy))throw new ChatFailure(422,"INVALID_CREDENTIAL_SOURCE");
        return Map.of("modelId",model,"credentialSource",policy);
    }
    @Override public Map<String,Object> effective(Actor a){return tx(true,c->{auth(c,a);var p=preferences(c,a);var resolved=resolve(c,a,null,null);var values=Map.of("modelId",resolved.get("modelId").toString(),"credentialSource",resolved.get("credentialSource"));
        return Map.of("values",values,"sources",Map.of("modelId",p.get("defaultModelId")==null?"PLATFORM":"USER","credentialSource",((Number)p.get("version")).longValue()==0?"PLATFORM":"USER"),"lockedFields",List.of(),"configHash",hash(json(new TreeMap<>(values))));
    });}
    @Override public Map<String,Object> configuration(Actor a,UUID id){return tx(true,c->{auth(c,a);task(c,a,id,false);
        try(var st=q(c,"SELECT s.*,t.bundle_id,k.status='ACTIVE' AND k.revision=s.credential_revision AS credential_active,m.status='ACTIVE' AS model_active FROM pf.task t JOIN pf.chat_config_snapshot s ON s.id=t.config_snapshot_id JOIN pf.credential k ON k.id=s.credential_id JOIN pf.model_definition m ON m.id=s.model_id WHERE t.id=? AND t.space_id=?",id,a.spaceId());var r=st.executeQuery()){
            if(!r.next())throw new ChatFailure(404,"RESOURCE_NOT_FOUND");var values=new TreeMap<>(object(r.getString("config_json")));
            values.keySet().retainAll(Set.of("modelId","modelName","modelVersion","credentialId","credentialRevision","credentialSource","window","maxOutput","thinking","pricingVersion","inputPrice","outputPrice","perCallMicros","bundleHash"));
            return Map.of("configSnapshotId",r.getObject("id").toString(),"configHash",r.getString("config_hash"),"bundleId",r.getObject("bundle_id").toString(),"values",values,"restrictions",Map.of("credentialActive",r.getBoolean("credential_active"),"modelActive",r.getBoolean("model_active")));
        }
    });}
    private static Map<String,Object> credentialMetadata(Connection c,Actor a)throws SQLException{
        long version=0;boolean active=false;
        try(var st=q(c,"SELECT u.version,k.status FROM pf.user_model_credential u LEFT JOIN pf.credential k ON k.id=u.credential_id WHERE u.space_id=? AND u.user_id=? AND u.provider='DEEPSEEK'",a.spaceId(),a.userId());var r=st.executeQuery()){if(r.next()){version=r.getLong(1);active="ACTIVE".equals(r.getString(2));}}
        boolean platform;try(var st=q(c,"SELECT 1 FROM pf.model_binding b JOIN pf.credential k ON k.id=b.credential_id WHERE b.space_id=? AND b.user_id=? AND b.source_policy='PLATFORM' AND k.scope_type='PLATFORM' AND k.status='ACTIVE' LIMIT 1",a.spaceId(),a.userId());var r=st.executeQuery()){platform=r.next();}
        return Map.of("version",version,"userConfigured",active,"platformAvailable",platform);
    }
    @Override public Map<String,Object> credentialMetadata(Actor a){return tx(c->{auth(c,a);return credentialMetadata(c,a);});}
    @Override public Map<String,Object> replaceUserCredential(Actor a,long expected,Credential encrypted){return tx(c->{
        auth(c,a);if(encrypted!=null&&(!"USER".equals(encrypted.scope())||!a.spaceId().equals(encrypted.spaceId())||!a.userId().equals(encrypted.ownerId())))throw new ChatFailure(403,"CREDENTIAL_SCOPE_REJECTED");
        exec(c,"INSERT INTO pf.user_model_credential(space_id,user_id,provider) VALUES(?,?,'DEEPSEEK') ON CONFLICT DO NOTHING",a.spaceId(),a.userId());UUID prior;
        try(var st=q(c,"SELECT credential_id,version FROM pf.user_model_credential WHERE space_id=? AND user_id=? AND provider='DEEPSEEK' FOR UPDATE",a.spaceId(),a.userId());var r=st.executeQuery()){r.next();if(r.getLong(2)!=expected)throw new ChatFailure(412,"CREDENTIAL_VERSION_CONFLICT");prior=r.getObject(1,UUID.class);}
        if(prior!=null)exec(c,"UPDATE pf.credential SET status='REVOKED',revision=revision+1 WHERE id=? AND scope_type='USER' AND space_id=? AND owner_user_id=?",prior,a.spaceId(),a.userId());
        if(encrypted!=null)exec(c,"INSERT INTO pf.credential(id,scope_type,space_id,owner_user_id,ciphertext,nonce,key_version,revision) VALUES(?,'USER',?,?,?,?,?,?)",encrypted.id(),a.spaceId(),a.userId(),encrypted.ciphertext(),encrypted.nonce(),encrypted.keyVersion(),encrypted.revision());
        exec(c,"UPDATE pf.user_model_credential SET credential_id=?,version=version+1 WHERE space_id=? AND user_id=? AND provider='DEEPSEEK'",encrypted==null?null:encrypted.id(),a.spaceId(),a.userId());
        audit(c,a,encrypted==null?"USER_CREDENTIAL_REVOKED":"USER_CREDENTIAL_REPLACED",encrypted==null?a.userId():encrypted.id(),Uuid7.next());return credentialMetadata(c,a);
    });}
    private static Map<String,Object> frozen(Connection c,Lease l)throws SQLException{
        try(var st=q(c,"SELECT s.config_json FROM pf.task t JOIN pf.chat_config_snapshot s ON s.id=t.config_snapshot_id WHERE t.id=? AND t.space_id=?",l.taskId(),l.spaceId());var r=st.executeQuery()){if(!r.next())throw new ChatFailure(404,"RESOURCE_NOT_FOUND");return object(r.getString(1));}
    }
    private static long callAllowance(Connection c,Lease l)throws SQLException{return ((Number)frozen(c,l).getOrDefault("perCallMicros",24576L)).longValue();}
    private static void activeModel(Connection c,Lease l)throws SQLException{validateModel(c,l.modelId());}
    @Override public String modelName(Lease l){return tx(c->{owned(c,l,false);activeModel(c,l);return (String)frozen(c,l).get("modelName");});}

    @Override public boolean ready(){try{return tx(c->{try(var s=q(c,"SELECT to_regclass('pf.user_model_credential') IS NOT NULL AND to_regclass('pf.user_preference_revision') IS NOT NULL AND to_regclass('pf.task') IS NOT NULL AND to_regclass('pf.event_outbox') IS NOT NULL AND to_regclass('pf.acceptance_model_meter') IS NOT NULL AND (SELECT schema_version=1 FROM pf.baseline_version WHERE id=1)");var r=s.executeQuery()){return r.next()&&r.getBoolean(1);}});}catch(ChatFailure e){return false;}}
}
