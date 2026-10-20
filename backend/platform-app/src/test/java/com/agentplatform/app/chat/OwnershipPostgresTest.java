package com.agentplatform.app.chat;

import com.agentplatform.infrastructure.adapter.repository.chat.MyBatisChatRepository;

import static org.junit.jupiter.api.Assertions.*;
import com.agentplatform.domain.chat.ChatData.*;
import com.agentplatform.domain.chat.ChatFailure;
import com.agentplatform.domain.chat.ChatProvider;
import com.agentplatform.domain.chat.ChatRuntime;
import com.agentplatform.domain.chat.ExecutionLedger.*;
import com.agentplatform.infrastructure.chat.*;
import com.agentplatform.domain.identity.IdentityStore;
import com.agentplatform.types.harness.HarnessData;
import com.agentplatform.types.harness.HarnessData.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Real isolated PG; deterministic provider is not evidence for R2.4 real-model continuation. */
class OwnershipPostgresTest {
    static String url,password;
    static final UUID MODEL=UUID.fromString("0199e000-0000-7000-8000-000000000002");
    com.agentplatform.domain.chat.ChatRepository repository;
    com.zaxxer.hikari.HikariDataSource pool;
    Actor actor;
    final CredentialCipher cipher=new CredentialCipher(new byte[32]);
    @TempDir Path workspace;

    @BeforeAll static void migrate()throws Exception {
        url=System.getenv("R21_TEST_JDBC_URL");Assumptions.assumeTrue(url!=null,"R2.1 real PG not configured: NOT_RUN");
        assertTrue(url.startsWith("jdbc:postgresql://127.0.0.1:15443/ownership_test?") || ("1".equals(System.getenv("R2C_ACCEPTANCE")) && url.startsWith("jdbc:postgresql://127.0.0.1:15444/ownership_test?")) || ("1".equals(System.getenv("R22_ACCEPTANCE")) && url.startsWith("jdbc:postgresql://127.0.0.1:15445/ownership_test?")) || ("1".equals(System.getenv("R23_ACCEPTANCE")) && url.startsWith("jdbc:postgresql://127.0.0.1:15446/ownership_test?")) || ("1".equals(System.getenv("R24_ACCEPTANCE")) && url.startsWith("jdbc:postgresql://127.0.0.1:15447/ownership_test?")), "Refusing a database outside the explicitly named isolated fixture");
        password=Files.readString(Path.of(System.getenv("R21_TEST_PASSWORD_FILE"))).strip();
        var old=Flyway.configure().dataSource(url,"postgres",password).defaultSchema("pf").schemas("pf","pf_runtime").cleanDisabled(false).target("5").load();
        old.clean();old.migrate();
        try(var c=connect();var s=c.createStatement()){s.execute("INSERT INTO pf.dispatch_quarantine(id,reason_code) VALUES('00000000-0000-4000-8000-000000000006','V5_SURVIVES')");}
        Flyway.configure().dataSource(url,"postgres",password).defaultSchema("pf").schemas("pf","pf_runtime").target("8").load().migrate();
        assertEquals(8,count("SELECT max(version::int) FROM pf.flyway_schema_history WHERE success"));
        Flyway.configure().dataSource(url,"postgres",password).defaultSchema("pf").schemas("pf","pf_runtime").load().migrate();
        assertEquals(1,count("SELECT schema_version FROM pf.baseline_version WHERE id=1"));
        assertEquals(1,count("SELECT count(*) FROM pf.dispatch_quarantine WHERE reason_code='V5_SURVIVES'"));
        assertEquals(9,count("SELECT max(version::int) FROM pf.flyway_schema_history WHERE success"));
    }
    @BeforeEach void setup()throws Exception {
        sql("TRUNCATE pf.app_user CASCADE; TRUNCATE pf_runtime.state_slot; UPDATE pf.execution_slot SET task_id=NULL,owner_id=NULL,lease_until=NULL; INSERT INTO pf.execution_slot(id) VALUES(1) ON CONFLICT DO NOTHING");
        var config=new com.zaxxer.hikari.HikariConfig();config.setJdbcUrl(url);config.setUsername("postgres");config.setPassword(password);config.setMaximumPoolSize(4);config.setMinimumIdle(1);
        pool=new com.zaxxer.hikari.HikariDataSource(config);repository=com.agentplatform.app.chat.ChatPersistenceFixture.create(pool);
        var identity=com.agentplatform.app.identity.IdentityPersistenceFixture.create(pool);UUID user=identity.provision(UUID.randomUUID(),"r21alice","R21 Alice","unused-test-password-hash",true);
        actor=new Actor(user,identity.spaces(user).getFirst().id());
        UUID key=repository.importCredential(cipher.encrypt(UUID.randomUUID(),"PLATFORM",null,null,"R21_SENTINEL_NEVER_A_REAL_KEY"));repository.bind(MODEL,actor,key,"PLATFORM");
    }
    @AfterEach void close(){if(pool!=null)pool.close();}
    static Connection connect()throws SQLException{return DriverManager.getConnection(url,"postgres",password);}
    static void sql(String sql)throws SQLException{try(var c=connect();var s=c.createStatement()){s.execute(sql);}}
    static long count(String sql)throws SQLException{try(var c=connect();var s=c.createStatement();var r=s.executeQuery(sql)){assertTrue(r.next());return r.getLong(1);}}
    static String text(String sql)throws SQLException{try(var c=connect();var s=c.createStatement();var r=s.executeQuery(sql)){assertTrue(r.next());return r.getString(1);}}
    static void denied(String code,Runnable action){assertEquals(code,assertThrows(ChatFailure.class,action::run).code());}
    Task task(){var c=repository.create(actor,UUID.randomUUID(),"R2.1");return repository.submit(actor,c.id(),UUID.randomUUID(),MODEL,"hello😀");}
    Envelope envelope(Task task){return repository.publications(UUID.randomUUID()).stream().map(Publish::envelope).filter(e->e.aggregateId().equals(task.id())).findFirst().orElseThrow();}
    Lease lease(){var task=task();return repository.claim(envelope(task),UUID.randomUUID());}
    com.agentplatform.domain.chat.ChatData.Call started(Lease l){var call=repository.beginCall(l,false);repository.startCall(l,call,false);return call;}
    Turn turn(String text,List<HarnessData.Call> calls){return new Turn(UUID.randomUUID().toString(),1,new Round(text,calls,calls.isEmpty()?"stop":"tool_calls",10,2));}
    void commit(Lease l,UUID id,Turn turn,com.agentplatform.domain.chat.ChatData.Call call){repository.commitRound(l,id,turn,new CallResult(call,true,"fixture",false));}
    StoppedOwner proof(Lease l){return new StoppedOwner(l.ownerId(),l.taskId(),l.attemptId(),l.fence(),"a".repeat(64),"sha256:"+"b".repeat(64),Instant.now().minusSeconds(60),Instant.now(),Instant.now().plusMillis(1),UUID.randomUUID().toString().replace("-","").repeat(2));}
    void expire()throws SQLException{sql("UPDATE pf.execution_slot SET lease_until=now()-interval '1 second'");}

    @Test void authorizationChangedInsideMutationIsRejectedBeforeCommitAndEverythingRollsBack() throws Exception {
        Lease lease = lease();
        long events = count("SELECT count(*) FROM pf.platform_event");
        sql("CREATE FUNCTION pf.fixture_revoke_before_commit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "UPDATE pf.membership SET status='REVOKED' WHERE user_id='" + actor.userId() + "'; RETURN NEW; END $$; "
                + "CREATE TRIGGER fixture_revoke_before_commit AFTER UPDATE ON pf.message FOR EACH ROW EXECUTE FUNCTION pf.fixture_revoke_before_commit()");
        try {
            denied("RESOURCE_NOT_FOUND", () -> repository.delta(lease, "must roll back"));
            assertEquals("", text("SELECT blocks_json->0->>'text' FROM pf.message WHERE id='" + lease.assistantId() + "'"));
            assertEquals(events, count("SELECT count(*) FROM pf.platform_event"));
            assertEquals("ACTIVE", text("SELECT status FROM pf.membership WHERE user_id='" + actor.userId() + "'"));
        } finally {
            sql("DROP TRIGGER fixture_revoke_before_commit ON pf.message; DROP FUNCTION pf.fixture_revoke_before_commit()");
        }
    }

    @Test void twoClaimantsCommitOnlyOneOwnerAndRedeliveryIsHarmless()throws Exception {
        Task task=task();Envelope envelope=envelope(task);CountDownLatch start=new CountDownLatch(1);
        try(var threads=Executors.newFixedThreadPool(2)){
            var a=threads.submit(()->{start.await();return repository.claim(envelope,UUID.randomUUID());});
            var b=threads.submit(()->{start.await();return repository.claim(envelope,UUID.randomUUID());});start.countDown();
            assertEquals(1,java.util.stream.Stream.of(a.get(),b.get()).filter(Objects::nonNull).count());
        }
        assertEquals(1,count("SELECT count(*) FROM pf.attempt"));assertEquals(1,count("SELECT count(*) FROM pf.conversation_lease"));
        assertNull(repository.claim(envelope,UUID.randomUUID()));
        assertEquals(1,count("SELECT count(*) FROM pf.consumer_inbox WHERE disposition='CLAIMED'"));
    }
    @Test void expiredFenceRejectsAllExecutionAndSdkMutations()throws Exception {
        Lease l=lease();var call=started(l);repository.stateCas(l,"sdk",0,"{\"state\":1}".getBytes(StandardCharsets.UTF_8));expire();
        List<Runnable> writes=List.of(()->repository.heartbeat(l),()->repository.delta(l,"late"),()->repository.startCall(l,call,false),
                ()->repository.beginCall(l,false),()->repository.settle(l,call,new Round("late",List.of(),"stop",1,1),null,null,false),
                ()->repository.finish(l,"SUCCEEDED",null),()->repository.stateCas(l,"sdk",1,"{}".getBytes(StandardCharsets.UTF_8)),
                ()->repository.stateDelete(l,"sdk"),()->repository.sealCheckpoint(l,ChatRuntime.bundle()));
        for(Runnable write:writes)denied("EXECUTION_FENCE_LOST",write);
        denied("EXECUTION_FENCE_LOST",()->repository.stopped(l,"FAILED","fixture",null,"0".repeat(64)));
        denied("EXECUTION_FENCE_LOST",()->repository.stopUnconfirmed(l));
        assertEquals(1,count("SELECT version FROM pf_runtime.state_slot WHERE state_key LIKE '%/sdk'"));
        assertEquals(1,count("SELECT count(*) FROM pf.model_call WHERE status='STARTED'"));
        assertEquals(1,count("SELECT count(*) FROM pf.execution_slot WHERE task_id IS NOT NULL"));
    }
    @Test void dispatchGenerationAndForgedScopeAreRejected()throws Exception {
        Lease l=lease();sql("UPDATE pf.execution_dispatch SET generation=generation+1");denied("EXECUTION_FENCE_LOST",()->repository.check(l));
        sql("UPDATE pf.execution_dispatch SET generation=generation-1");
        var forged=new Lease(l.taskId(),l.spaceId(),UUID.randomUUID(),l.conversationId(),l.attemptId(),l.ownerId(),l.fence(),l.modelId(),l.credentialId(),l.credentialRevision(),l.assistantId());
        denied("EXECUTION_FENCE_LOST",()->repository.stateCas(forged,"x",0,"{}".getBytes(StandardCharsets.UTF_8)));
    }
    @Test void leaseExpiringDuringSqlRollsBackPrivateStateAtCommitBoundary()throws Exception {
        Lease l=lease();
        sql("CREATE FUNCTION pf.slow_state_fixture() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_sleep(0.5); RETURN NEW; END $$; CREATE TRIGGER slow_state_fixture BEFORE INSERT ON pf_runtime.state_slot FOR EACH ROW EXECUTE FUNCTION pf.slow_state_fixture()");
        try{
            sql("UPDATE pf.execution_slot SET lease_until=clock_timestamp()+interval '200 milliseconds'");
            denied("EXECUTION_FENCE_LOST",()->repository.stateCas(l,"slow",0,"{}".getBytes(StandardCharsets.UTF_8)));
            assertEquals(0,count("SELECT count(*) FROM pf_runtime.state_slot WHERE state_key LIKE '%/slow'"));
        }finally{sql("DROP TRIGGER slow_state_fixture ON pf_runtime.state_slot; DROP FUNCTION pf.slow_state_fixture()");}
    }
    @Test void completeRoundCallListAndAccountingRollbackTogether()throws Exception {
        Lease l=lease();UUID id=repository.beginTurn(l);var call=started(l);
        var tool=new HarnessData.Call("tool-1","fixture",Map.of("text","你好😀"),false);
        Turn invalid=new Turn(UUID.randomUUID().toString(),1,new Round("bad",List.of(tool),"tool_calls",8193,1));
        denied("PROVIDER_USAGE_EXCEEDS_RESERVATION",()->commit(l,id,invalid,call));
        assertEquals(0,count("SELECT count(*) FROM pf.tool_operation"));assertEquals(0,count("SELECT count(*) FROM pf.model_turn WHERE status='COMMITTED'"));
        assertEquals(1,count("SELECT count(*) FROM pf.budget_reservation WHERE state='RESERVED'"));
        Turn valid=turn("",List.of(tool));commit(l,id,valid,call);commit(l,id,valid,call);
        assertEquals(1,count("SELECT count(*) FROM pf.tool_operation"));assertEquals(1,count("SELECT count(*) FROM pf.model_turn WHERE status='COMMITTED'"));
        assertEquals(1,count("SELECT count(*) FROM pf.platform_event WHERE type='usage.updated'"));
        assertEquals(1,count("SELECT count(*) FROM pf.model_call WHERE turn_id='"+id+"' AND status='COMPLETED'"));
        repository.startTool(l,tool);
        denied("OPERATION_NOT_PREPARED",()->repository.startTool(l,tool));
        var result=new Observation(tool.id(),tool.name(),"done",OperationStatus.SUCCEEDED);
        repository.commitTool(l,tool,result);repository.commitTool(l,tool,result);
        assertEquals(1,count("SELECT count(*) FROM pf.tool_operation WHERE status='SUCCEEDED'"));
        denied("TURN_CONTENT_CONFLICT",()->commit(l,id,turn("changed",List.of(tool)),call));
    }
    @Test void checkpointIsImmutableAndDetectsMissingCorruptAndIncompatibleState()throws Exception {
        Lease l=lease();UUID id=repository.beginTurn(l);var call=started(l);commit(l,id,turn("answer😀",List.of()),call);
        repository.stateCas(l,"sdk",0,"{\"user\":\"alice\",\"state\":1}".getBytes(StandardCharsets.UTF_8));
        UUID checkpoint=repository.sealCheckpoint(l,ChatRuntime.bundle());repository.validateCheckpoint(l,checkpoint,ChatRuntime.bundle());
        String frozen=text("SELECT state_hash FROM pf.checkpoint WHERE id='"+checkpoint+"'");
        repository.stateCas(l,"sdk",1,"{\"state\":2}".getBytes(StandardCharsets.UTF_8));
        assertEquals(frozen,text("SELECT state_hash FROM pf.checkpoint WHERE id='"+checkpoint+"'"));
        assertThrows(SQLException.class,()->sql("UPDATE pf.checkpoint SET state_hash='wrong' WHERE id='"+checkpoint+"'"));
        denied("CHECKPOINT_NOT_COMMITTED",()->repository.validateCheckpoint(l,UUID.randomUUID(),ChatRuntime.bundle()));
        var bundle=ChatRuntime.bundle();var incompatible=new Bundle(bundle.sdkVersion(),bundle.contractVersion(),bundle.stateSchemaVersion(),"wrong-codec",bundle.modelFamily(),bundle.components());
        denied("BUNDLE_INCOMPATIBLE",()->repository.validateCheckpoint(l,checkpoint,incompatible));
        UUID corrupt=UUID.randomUUID();
        sql("INSERT INTO pf.checkpoint SELECT '"+corrupt+"',space_id,task_id,attempt_id,fencing_token,status,bundle_id,bundle_hash,manifest_json,manifest_hash,canonical_json,canonical_hash,watermark,state_json,'bad',created_at FROM pf.checkpoint WHERE id='"+checkpoint+"'");
        denied("CHECKPOINT_HASH_MISMATCH",()->repository.validateCheckpoint(l,corrupt,ChatRuntime.bundle()));
        denied("CHECKPOINT_TARGET_NOT_NEW_ATTEMPT",()->repository.restoreCheckpoint(l,checkpoint,ChatRuntime.bundle()));
        // Future continuation target fixture: no public resume command is delivered in R2.1.
        // The old owner is proven stopped first; a later-stage coordinator will own this transition.
        expire();repository.recoverStopped(proof(l));
        sql("UPDATE pf.task SET status='QUEUED'; UPDATE pf.execution_dispatch SET status='READY',generation=generation+1");
        var target=repository.claim(new Envelope(1,UUID.randomUUID(),l.spaceId(),l.taskId(),2,"execution.ready",Instant.now(),null,l.taskId()),UUID.randomUUID());
        repository.restoreCheckpoint(target,checkpoint,ChatRuntime.bundle());
        assertTrue(new String(repository.state(target,"sdk").body(),StandardCharsets.UTF_8).contains("alice"));
        assertEquals(1,repository.state(target,"sdk").version());
        assertEquals(2,count("SELECT version FROM pf_runtime.state_slot WHERE state_key='attempt/"+l.attemptId()+"/sdk'"));
        denied("CHECKPOINT_TARGET_NOT_EMPTY",()->repository.restoreCheckpoint(target,checkpoint,ChatRuntime.bundle()));
    }
    @Test void revocationRejectsPrivateStateAndAcceptedResults()throws Exception {
        Lease l=lease();UUID turn=repository.beginTurn(l);var call=started(l);repository.revokeCredential(l.credentialId());
        denied("CREDENTIAL_REVOKED",()->repository.stateCas(l,"sdk",0,"{}".getBytes(StandardCharsets.UTF_8)));
        denied("CREDENTIAL_REVOKED",()->commit(l,turn,turn("late",List.of()),call));
        assertEquals(0,count("SELECT count(*) FROM pf.model_turn WHERE status='COMMITTED'"));
    }
    @Test void bareProofAndLiveOrStaleProofCannotReleaseSlot()throws Exception {
        Lease l=lease();denied("STOPPED_OWNER_PROOF_REQUIRED",()->repository.recoverStopped(l.ownerId()));
        denied("OWNER_LEASE_NOT_EXPIRED",()->repository.recoverStopped(proof(l)));expire();
        var p=proof(l);var stale=new StoppedOwner(p.ownerId(),p.taskId(),p.attemptId(),p.fence(),p.containerId(),p.imageId(),p.startedAt().minusSeconds(600),p.stoppedAt().minusSeconds(600),p.observedAt().minusSeconds(600),p.evidenceHash());
        denied("STOPPED_OWNER_PROOF_STALE",()->repository.recoverStopped(stale));
        var other=new StoppedOwner(UUID.randomUUID(),p.taskId(),p.attemptId(),p.fence(),p.containerId(),p.imageId(),p.startedAt(),p.stoppedAt(),p.observedAt(),p.evidenceHash());
        denied("STOPPED_OWNER_PROOF_MISMATCH",()->repository.recoverStopped(other));
        assertEquals(1,count("SELECT count(*) FROM pf.execution_slot WHERE task_id IS NOT NULL"));
    }
    @Test void stoppedBeforeRequestRequeuesSameTaskWithNewGenerationAndNoBudgetInflation()throws Exception {
        Lease l=lease();repository.beginCall(l,false);long reserve=count("SELECT reserved_tokens FROM pf.budget_account");
        expire();var proof=proof(l);repository.recoverStopped(proof);repository.recoverStopped(proof);
        assertEquals("QUEUED",repository.task(actor,l.taskId()).status());assertEquals(2,count("SELECT generation FROM pf.execution_dispatch"));
        assertEquals(reserve,count("SELECT reserved_tokens FROM pf.budget_account"));assertEquals(36864,count("SELECT remaining_tokens FROM pf.task"));
        var next=repository.publications(UUID.randomUUID()).stream().map(Publish::envelope).filter(e->e.generation()==2).findFirst().orElseThrow();
        Lease second=repository.claim(next,UUID.randomUUID());assertNotNull(second);assertEquals(l.taskId(),second.taskId());assertNotEquals(l.attemptId(),second.attemptId());
        var latestState=repository.events(actor,l.conversationId(),0).stream().filter(e->e.type().equals("task.state_changed")).toList().getLast();
        assertEquals(repository.task(actor,l.taskId()).version(),latestState.version(),"Reclaimed RUNNING events must not regress to the first claim's version2");
        assertEquals("RUNNING",latestState.payload().get("status"));
        denied("EXECUTION_FENCE_LOST",()->repository.stateDelete(l,"sdk"));
    }
    @Test void responseBeforeRoundCommitRemainsUnknownAndNeverRequeues()throws Exception {
        Lease l=lease();started(l);repository.delta(l,"partial😀");expire();repository.recoverStopped(proof(l));
        assertEquals("BLOCKED",repository.task(actor,l.taskId()).status());
        assertEquals(1,count("SELECT count(*) FROM pf.model_call WHERE status='UNKNOWN'"));assertEquals(1,count("SELECT count(*) FROM pf.budget_reservation WHERE state='RECONCILING'"));
        assertEquals(9216,count("SELECT reserved_tokens FROM pf.budget_account"));assertEquals(1,count("SELECT count(*) FROM pf.message WHERE status='PARTIAL'"));
        assertEquals(1,count("SELECT generation FROM pf.execution_dispatch"));
    }
    @Test void committedTerminalRoundConvergesWithoutAnotherPhysicalCall()throws Exception {
        Lease l=lease();UUID id=repository.beginTurn(l);var call=started(l);commit(l,id,turn("durable answer😀",List.of()),call);
        expire();repository.recoverStopped(proof(l));
        assertEquals("SUCCEEDED",repository.task(actor,l.taskId()).status());assertEquals(1,count("SELECT count(*) FROM pf.model_call"));
        assertEquals("durable answer😀",text("SELECT blocks_json->0->>'text' FROM pf.message WHERE role='ASSISTANT'"));
        assertEquals(0,count("SELECT reserved_tokens FROM pf.budget_account"));
    }
    @Test void missingUsageDoesNotBecomeZeroAndToolRunningCrashIsUnknown()throws Exception {
        Lease l=lease();UUID id=repository.beginTurn(l);var call=started(l);var tool=new HarnessData.Call("side-effect","fixture",Map.of(),false);Turn turn=turn("",List.of(tool));
        repository.commitRound(l,id,turn,new CallResult(call,false,"no-usage",false));repository.startTool(l,tool);
        assertEquals(1,count("SELECT count(*) FROM pf.model_call WHERE input_tokens IS NULL AND output_tokens IS NULL"));
        expire();repository.recoverStopped(proof(l));
        assertEquals("BLOCKED",repository.task(actor,l.taskId()).status());assertEquals(1,count("SELECT count(*) FROM pf.tool_operation WHERE status='UNKNOWN'"));
        assertEquals(9216,count("SELECT reserved_tokens FROM pf.budget_account"));
    }
    @Test void actualSdkRoundPersistsCheckpointAndSettlesOnce()throws Exception {
        Lease l=lease();AtomicInteger calls=new AtomicInteger();
        var provider=new ChatProvider(){public boolean real(){return false;}public CompletionStage<Result> stream(String key,List<HarnessData.Message> input,java.util.function.Consumer<String> text){calls.incrementAndGet();text.accept("stream😀");return CompletableFuture.completedFuture(new Result(new Round("stream😀",List.of(),"stop",10,2),true,"fixture-only"));}};
        try(var execution=new ChatExecution(repository,provider,cipher,workspace)){execution.run(l);}
        var task=repository.task(actor,l.taskId());assertEquals("SUCCEEDED",task.status(),task.reasonCode());assertEquals(1,calls.get());
        assertEquals(1,count("SELECT count(*) FROM pf.model_turn WHERE status='COMMITTED'"));
        assertTrue(count("SELECT count(*) FROM pf.checkpoint")>=1);assertEquals(0,count("SELECT physical_calls FROM pf.acceptance_model_meter"));
    }
    @Test void oldFrozenBundleBlocksBeforeAnyPhysicalRequestAndIsNeverRewritten()throws Exception {
        Task task=task();Envelope envelope=envelope(task);
        sql("UPDATE pf.runtime_bundle SET manifest_hash='old-frozen-r1' WHERE id='"+MyBatisChatRepository.BUNDLE+"'");
        assertNull(repository.claim(envelope,UUID.randomUUID()));
        assertEquals("BLOCKED",repository.task(actor,task.id()).status());
        assertEquals("BUNDLE_INCOMPATIBLE",repository.task(actor,task.id()).reasonCode());
        assertEquals(0,count("SELECT count(*) FROM pf.model_call"));
        assertEquals("old-frozen-r1",text("SELECT manifest_hash FROM pf.runtime_bundle WHERE id='"+MyBatisChatRepository.BUNDLE+"'"));
        // Restore only the test-created manifest row so subsequent independent test cases can submit.
        sql("UPDATE pf.runtime_bundle SET manifest_hash='"+ChatRuntime.bundle().hash()+"' WHERE id='"+MyBatisChatRepository.BUNDLE+"'");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void realSdkToolBoundaryDoesNotRepeatUncertainExternalEffect(boolean effectStarted)throws Exception {
        Lease l=lease();AtomicInteger effects=new AtomicInteger();
        var call=new HarnessData.Call("external-fixture","fixture",Map.of("text","x"),false);
        var logical=new java.util.concurrent.atomic.AtomicReference<UUID>();var physical=new java.util.concurrent.atomic.AtomicReference<com.agentplatform.domain.chat.ChatData.Call>();
        var scope=new Scope(l.spaceId().toString(),l.userId().toString(),l.conversationId().toString(),l.taskId().toString(),l.attemptId().toString(),l.fence(),false);
        var storage=new com.agentplatform.domain.harness.HarnessPorts.StateStorage(){
            public StateSlot load(Scope s,String k){return repository.state(l,k);}
            public long compareAndSwap(Scope s,String k,long v,byte[] b){return repository.stateCas(l,k,v,b);}
            public Set<String> keys(Scope s){return repository.stateKeys(l);}
            public void delete(Scope s,String k){repository.stateDelete(l,k);}
        };
        var manifests=new com.agentplatform.domain.harness.HarnessPorts.RuntimeStatePort(){
            public void verifyRestore(Scope s,Bundle b){throw new AssertionError("No process continuation claim");}
            public void seal(Scope s,Bundle b){repository.sealCheckpoint(l,b);}
            public List<HarnessData.Message> checkpointHistory(Scope s){return repository.checkpointHistory(l);}
        };
        var driver=new com.agentplatform.infrastructure.harness.agentscope.AgentScopeRuntimeFactory(
                (s,h,t)->{physical.set(started(l));return CompletableFuture.completedFuture(new Round("",List.of(call),"tool_calls",10,2));},
                (s,c)->Decision.ALLOW,(s,c)->{if(effectStarted)effects.incrementAndGet();return CompletableFuture.failedFuture(new IllegalStateException("FIXTURE_CRASH_BEFORE_RESULT_COMMIT"));},
                storage,manifests,List.of(call),workspace);
        var boundary=new com.agentplatform.domain.harness.HarnessPorts.LoopBoundary(){
            public CompletionStage<List<HarnessData.Message>> beforeModel(Scope s,List<HarnessData.Message> h){logical.set(repository.beginTurn(l));return CompletableFuture.completedFuture(repository.history(l));}
            public CompletionStage<Void> afterModelCommitted(Scope s,Turn t){commit(l,logical.get(),t,physical.get());return CompletableFuture.completedFuture(null);}
            public CompletionStage<Void> beforeTool(Scope s,HarnessData.Call c){repository.startTool(l,c);return CompletableFuture.completedFuture(null);}
            public CompletionStage<Void> afterToolCommitted(Scope s,HarnessData.Call c,Observation o){repository.commitTool(l,c,o);return CompletableFuture.completedFuture(null);}
            public CompletionStage<Completion> beforeStop(Scope s,HarnessData.Message m){return CompletableFuture.completedFuture(Completion.PASS);}
        };
        try(var run=driver.prepare(new Request(scope,ChatRuntime.bundle(),List.of(HarnessData.Message.text(Role.USER,"fixture tool")),false,List.of(),1),boundary)){
            assertThrows(ExecutionException.class,()->run.start().toCompletableFuture().get(15,TimeUnit.SECONDS));
        }
        assertEquals(effectStarted?1:0,effects.get());assertEquals(1,count("SELECT count(*) FROM pf.tool_operation WHERE status='RUNNING'"));
        expire();repository.recoverStopped(proof(l));
        assertEquals(1,count("SELECT count(*) FROM pf.tool_operation WHERE status='UNKNOWN'"));assertEquals("BLOCKED",repository.task(actor,l.taskId()).status());
        assertEquals(effectStarted?1:0,effects.get());
    }
}
