package com.agentplatform.app.chat;

import com.agentplatform.domain.chat.*;
import com.agentplatform.domain.chat.ChatData.*;
import com.agentplatform.domain.chat.RecoveryLedger.*;
import com.agentplatform.infrastructure.chat.CredentialCipher;
import com.agentplatform.infrastructure.harness.agentscope.PlatformMessageCodec;
import com.agentplatform.types.harness.HarnessData.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Trusted local fault-injection probe, never an application adapter or proof of executor termination.
 * Uses the accepted image's MyBatis/Spring repository; JDBC only injects isolated test corruption.
 * No executor, supplier, tool or queue consumer is launched by this probe. */
public final class RecoveryIntegrityProbe {
    private static final ObjectMapper JSON=new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final UUID MODEL=UUID.fromString("0199e000-0000-7000-8000-000000000002");
    private final ChatRepository repository;
    private final com.agentplatform.domain.identity.IdentityStore identities;
    private final com.zaxxer.hikari.HikariDataSource pool;
    private final CredentialCipher cipher;
    private final String passwordHash;
    private record Source(Actor actor,Lease lease,UUID checkpoint) {}
    private record Copy(Map<String,Object> manifest,Map<String,Object> state,String canonical,String canonicalHash,
                        String bundleHash,long watermark) {}

    private RecoveryIntegrityProbe()throws Exception {
        var config=new com.zaxxer.hikari.HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://postgres:5432/chat?connectTimeout=2&socketTimeout=5");
        config.setUsername("postgres");config.setPassword(Files.readString(Path.of("/run/secrets/pg")).strip());
        config.setMaximumPoolSize(4);config.setMinimumIdle(1);pool=new com.zaxxer.hikari.HikariDataSource(config);
        repository=ChatPersistenceFixture.create(pool);identities=com.agentplatform.app.identity.IdentityPersistenceFixture.create(pool);
        cipher=new CredentialCipher(Files.readAllBytes(Path.of("/run/secrets/master")));
        passwordHash=new org.springframework.security.crypto.argon2.Argon2PasswordEncoder(16,32,1,65536,3).encode(UUID.randomUUID().toString());
    }
    private Source source()throws Exception {
        String username="r24i"+UUID.randomUUID().toString().replace("-","").substring(0,20);
        UUID user=identities.provision(UUID.randomUUID(),hash(username),username,passwordHash,false);
        var actor=new Actor(user,identities.spaces(user).getFirst().id());
        UUID credential=repository.importCredential(cipher.encrypt(UUID.randomUUID(),"PLATFORM",null,null,"INTEGRITY_FIXTURE_NOT_A_REAL_KEY"));
        repository.bind(MODEL,actor,credential,"PLATFORM");
        var conversation=repository.create(actor,UUID.randomUUID(),"Integrity fixture");
        var task=repository.submit(actor,conversation.id(),UUID.randomUUID(),MODEL,"integrity probe");
        var envelope=repository.publications(UUID.randomUUID()).stream().map(Publish::envelope).filter(e->task.id().equals(e.aggregateId())).findFirst().orElseThrow();
        var lease=repository.claim(envelope,UUID.randomUUID());assertNotNull(lease);
        var startup=repository.executionStartup(lease);
        var state=io.agentscope.core.state.AgentState.builder().userId(user.toString()).sessionId(conversation.id().toString()).build();
        var codec=new PlatformMessageCodec();startup.history().stream().map(codec::encode).forEach(state.contextMutable()::add);
        byte[] body=new io.agentscope.core.util.JacksonJsonCodec().toJson(state).getBytes(StandardCharsets.UTF_8);
        var checkpoint=repository.commitCheckpoint(lease,ChatRuntime.bundle(),new CheckpointDraft(0,body,startup.history(),CheckpointPhase.INPUT_READY));
        repository.stopped(lease,"BLOCKED","NO_EXECUTOR_LAUNCHED_FIXTURE",null,"a".repeat(64));
        return new Source(actor,lease,checkpoint);
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(String value)throws Exception{return JSON.readValue(value,Map.class);}
    private static String json(Object value)throws Exception{return JSON.writeValueAsString(value);}
    private static String hash(String value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
    private Copy copy(Source source)throws Exception {
        try(var c=pool.getConnection();var s=c.prepareStatement("SELECT manifest_json::text,manifest_hash,state_json::text,state_hash,canonical_json::text,canonical_hash,bundle_hash,watermark FROM pf.checkpoint WHERE id=?")) {
            s.setObject(1,source.checkpoint());try(var r=s.executeQuery()){assertTrue(r.next());var manifest=map(r.getString(1));var state=map(r.getString(3));
                assertEquals(r.getString(2),hash(json(manifest)),"Probe canonicalization must match the accepted image");
                assertEquals(r.getString(4),hash(json(state)));
                return new Copy(manifest,state,r.getString(5),r.getString(6),r.getString(7),r.getLong(8));}
        }
    }
    private void insert(Source source,Copy copy,String manifestHash)throws Exception {
        String state=json(copy.state());copy.manifest().put("stateHash",hash(state));String manifest=json(copy.manifest());
        try(var c=pool.getConnection();var s=c.prepareStatement("INSERT INTO pf.checkpoint(id,space_id,task_id,attempt_id,fencing_token,status,bundle_id,bundle_hash,manifest_json,manifest_hash,canonical_json,canonical_hash,watermark,state_json,state_hash,created_at,manifest_version,phase) SELECT ?,space_id,task_id,attempt_id,fencing_token,status,bundle_id,?,?::jsonb,?,?::jsonb,?,?,?::jsonb,?,clock_timestamp(),manifest_version,phase FROM pf.checkpoint WHERE id=?")) {
            s.setObject(1,UUID.randomUUID());s.setString(2,copy.bundleHash());s.setString(3,manifest);s.setString(4,manifestHash==null?hash(manifest):manifestHash);
            s.setString(5,copy.canonical());s.setString(6,copy.canonicalHash());s.setLong(7,copy.watermark());s.setString(8,state);s.setString(9,hash(state));s.setObject(10,source.checkpoint());assertEquals(1,s.executeUpdate());
        }
    }
    private RestoreCommand command(Source source) {
        var view=repository.recovery(source.actor(),source.lease().taskId());
        return new RestoreCommand(UUID.randomUUID(),view.taskId(),view.checkpointId(),source.lease().attemptId(),view.taskVersion(),view.revision(),"trusted-local-integrity-probe");
    }
    private void refused(Source source,String code)throws Exception {
        var failure=assertThrows(ChatFailure.class,()->repository.restore(command(source)));assertEquals(code,failure.code());
        try(var c=pool.getConnection();var s=c.prepareStatement("SELECT (SELECT count(*) FROM pf.checkpoint_recovery WHERE task_id=?)+(SELECT count(*) FROM pf.model_call WHERE task_id=?)")) {
            s.setObject(1,source.lease().taskId());s.setObject(2,source.lease().taskId());try(var r=s.executeQuery()){assertTrue(r.next());assertEquals(0,r.getLong(1));}
        }
        System.out.println("PASS integrity refusal "+code);
    }
    @SuppressWarnings("unchecked") private void body(Copy copy,String value)throws Exception {
        var state=(Map<String,Object>)copy.state().get("agent_state");String digest=hash(value);
        state.put("body",Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));state.put("hash",digest);
        var states=(Map<String,Object>)copy.manifest().get("states");((Map<String,Object>)states.get("agent_state")).put("hash",digest);
    }
    @SuppressWarnings("unchecked") private void run()throws Exception {
        var s=source();var cp=copy(s);insert(s,cp,"0".repeat(64));refused(s,"CHECKPOINT_HASH_MISMATCH");
        s=source();cp=copy(s);cp.state().clear();cp.manifest().put("states",Map.of());insert(s,cp,null);refused(s,"CHECKPOINT_STATE_MISSING");
        s=source();cp=copy(s);((Map<String,Object>)cp.manifest().get("bundle")).put("codecVersion","unsupported-codec");insert(s,cp,null);refused(s,"BUNDLE_INCOMPATIBLE");
        s=source();cp=copy(s);cp=new Copy(cp.manifest(),cp.state(),cp.canonical(),cp.canonicalHash(),"b".repeat(64),cp.watermark());insert(s,cp,null);refused(s,"BUNDLE_INCOMPATIBLE");
        s=source();cp=copy(s);cp.manifest().put("watermark",1);cp=new Copy(cp.manifest(),cp.state(),cp.canonical(),cp.canonicalHash(),cp.bundleHash(),1);insert(s,cp,null);refused(s,"CHECKPOINT_LEDGER_ADVANCED");
        s=source();cp=copy(s);body(cp,json(Map.of("user_id",s.actor().userId().toString(),"session_id",s.lease().conversationId().toString(),"context","invalid")));insert(s,cp,null);refused(s,"CHECKPOINT_STATE_INVALID");
        s=source();cp=copy(s);body(cp,json(Map.of("user_id",UUID.randomUUID().toString(),"session_id",s.lease().conversationId().toString(),"context",List.of())));insert(s,cp,null);refused(s,"CHECKPOINT_STATE_SCOPE_MISMATCH");
        s=source();try(var c=pool.getConnection();var q=c.prepareStatement("UPDATE pf.budget_account SET cost_limit_micros=greatest(1,reserved_micros+consumed_micros) WHERE user_id=?")){q.setObject(1,s.actor().userId());q.executeUpdate();}
        refused(s,"QUOTA_EXCEEDED");
        s=source();repository.cancel(s.actor(),s.lease().taskId(),UUID.randomUUID());refused(s,"RECOVERY_CANCELLED");
        var race=source();var first=command(race);var second=command(race);
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var threads=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a=threads.submit(()->{start.await();try{return repository.restore(first).stage();}catch(ChatFailure failure){return failure.code();}});
            var b=threads.submit(()->{start.await();try{return repository.restore(second).stage();}catch(ChatFailure failure){return failure.code();}});
            start.countDown();var results=List.of(a.get(),b.get());
            assertEquals(1,results.stream().filter("QUEUED"::equals).count());assertTrue(results.contains("RECOVERY_VERSION_CONFLICT"));
        }
        try(var c=pool.getConnection();var q=c.prepareStatement("SELECT reserved_tokens FROM pf.budget_account WHERE user_id=?")) {
            q.setObject(1,race.actor().userId());try(var r=q.executeQuery()){assertTrue(r.next());assertEquals(36864,r.getLong(1));}
        }
        repository.cancel(race.actor(),race.lease().taskId(),UUID.randomUUID());
        System.out.println("PASS concurrent command CAS; one reservation, no executor or supplier request");
        System.out.println("PASS integrity-refusals:10; current immutable image, real PostgreSQL, no executor or supplier request");
    }
    public static void main(String[] args)throws Exception {
        var probe=new RecoveryIntegrityProbe();try{probe.run();}finally{probe.pool.close();}
    }
}
