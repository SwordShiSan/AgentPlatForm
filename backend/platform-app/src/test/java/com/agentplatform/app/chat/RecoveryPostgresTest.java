package com.agentplatform.app.chat;

import com.agentplatform.domain.chat.ChatData.*;
import com.agentplatform.domain.chat.ChatRuntime;
import com.agentplatform.domain.chat.RecoveryLedger.*;
import com.agentplatform.types.harness.HarnessData.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.agentplatform.app.chat.OwnershipPostgresTest.*;

/** Real PostgreSQL transaction tests. Local receipts represent an executor never launched by this fixture. */
class RecoveryPostgresTest {
    @TempDir Path directory;
    OwnershipPostgresTest fixture;
    @BeforeAll static void migrate() throws Exception { OwnershipPostgresTest.migrate(); }
    @BeforeEach void setup() throws Exception {
        fixture=new OwnershipPostgresTest();fixture.workspace=directory;fixture.setup();
    }
    @AfterEach void close() { if(fixture!=null)fixture.close(); }

    record Candidate(Lease lease, UUID checkpoint, Recovery view) {}
    Candidate stoppedInput() {
        var repository=fixture.repository;var lease=fixture.lease();
        var startup=repository.executionStartup(lease);
        assertFalse(startup.restoring());assertEquals(1,startup.nextTurn());
        byte[] sdk=("{\"user_id\":\""+lease.userId()+"\",\"session_id\":\""+lease.conversationId()+"\",\"context\":[]}").getBytes(StandardCharsets.UTF_8);
        UUID checkpoint=repository.commitCheckpoint(lease,ChatRuntime.bundle(),new CheckpointDraft(0,sdk,startup.history(),CheckpointPhase.INPUT_READY));
        repository.stopped(lease,"BLOCKED","WORKER_INTERRUPTED",null,"0".repeat(64));
        return new Candidate(lease,checkpoint,repository.recovery(fixture.actor,lease.taskId()));
    }
    RestoreCommand command(Candidate c) {
        return new RestoreCommand(UUID.randomUUID(),c.lease().taskId(),c.checkpoint(),c.lease().attemptId(),c.view().taskVersion(),c.view().revision(),"local-fixture-operator");
    }
    @Test void atomicInputSnapshotRestoresIntoNewAttemptWithoutChangingIdentityOrDeadline() throws Exception {
        var c=stoppedInput();var repository=fixture.repository;
        assertTrue(c.view().eligible());assertEquals("OPERATOR_VALIDATION_REQUIRED",c.view().reasonCode());
        String deadline=text("SELECT execution_deadline_at::text FROM pf.task WHERE id='"+c.lease().taskId()+"'");
        var cmd=command(c);assertEquals("QUEUED",repository.restore(cmd).stage());
        repository.restore(cmd);
        assertEquals(1,count("SELECT count(*) FROM pf.checkpoint_recovery"));
        var task=repository.task(fixture.actor,c.lease().taskId());
        var target=repository.claim(fixture.envelope(task),UUID.randomUUID());assertNotNull(target);
        assertEquals(c.lease().taskId(),target.taskId());assertEquals(c.lease().assistantId(),target.assistantId());
        assertNotEquals(c.lease().attemptId(),target.attemptId());assertTrue(target.fence()>c.lease().fence());
        var startup=repository.executionStartup(target);assertTrue(startup.restoring());assertEquals(1,startup.nextTurn());
        assertEquals(c.checkpoint(),startup.checkpointId());
        assertEquals(1,repository.state(target,"agent_state").version());
        assertEquals(deadline,text("SELECT execution_deadline_at::text FROM pf.task WHERE id='"+target.taskId()+"'"));
        assertEquals(2,count("SELECT count(*) FROM pf.message"));assertEquals(0,count("SELECT count(*) FROM pf.model_call"));
        denied("EXECUTION_FENCE_LOST",()->repository.stateCas(c.lease(),"late",0,new byte[]{1}));
    }
    @Test void conflictingCommandAndStaleRevisionNeverReserveTwice() throws Exception {
        var c=stoppedInput();var cmd=command(c);fixture.repository.restore(cmd);
        long reserved=count("SELECT reserved_tokens FROM pf.budget_account");
        denied("RECOVERY_VERSION_CONFLICT",()->fixture.repository.restore(command(c)));
        var changed=new RestoreCommand(cmd.commandId(),cmd.taskId(),cmd.checkpointId(),cmd.stopAttemptId(),cmd.expectedTaskVersion(),cmd.expectedRevision(),"other-operator");
        denied("IDEMPOTENCY_CONFLICT",()->fixture.repository.restore(changed));
        assertEquals(reserved,count("SELECT reserved_tokens FROM pf.budget_account"));
    }
    @Test void deadlineDoesNotResetWhenRecoveryIsRequested() throws Exception {
        var c=stoppedInput();sql("UPDATE pf.task SET execution_deadline_at=clock_timestamp()-interval '1 second'");
        denied("RECOVERY_DEADLINE_EXCEEDED",()->fixture.repository.restore(command(c)));
        assertEquals(0,count("SELECT count(*) FROM pf.checkpoint_recovery"));
    }
    @Test void aLaterIndependentTaskPreventsRestoringOldContext() {
        var c=stoppedInput();fixture.repository.submit(fixture.actor,c.lease().conversationId(),UUID.randomUUID(),MODEL,"independent later message");
        denied("RECOVERY_CONVERSATION_ADVANCED",()->fixture.repository.restore(command(c)));
    }
    @Test void unknownPhysicalCallIsNeverReplayed() throws Exception {
        var c=stoppedInput();fixture.repository.restore(command(c));
        var target=fixture.repository.claim(fixture.envelope(fixture.repository.task(fixture.actor,c.lease().taskId())),UUID.randomUUID());
        var call=fixture.repository.beginCall(target,false);fixture.repository.startCall(target,call,false);
        fixture.repository.stopped(target,"BLOCKED","WORKER_INTERRUPTED",null,"1".repeat(64));
        var projection=fixture.repository.recovery(fixture.actor,target.taskId());
        assertFalse(projection.eligible());assertEquals("RECOVERY_UNKNOWN_EFFECT",projection.reasonCode());
        assertEquals(1,count("SELECT count(*) FROM pf.model_call WHERE status='UNKNOWN'"));
        assertEquals(1,count("SELECT count(*) FROM pf.budget_reservation WHERE state='RECONCILING'"));
    }
    @Test void inputCheckpointCasConflictRollsBackSnapshotAndStateTogether() {
        var l=fixture.lease();var startup=fixture.repository.executionStartup(l);
        byte[] body="{}".getBytes(StandardCharsets.UTF_8);
        denied("STATE_CAS_CONFLICT",()->fixture.repository.commitCheckpoint(l,ChatRuntime.bundle(),new CheckpointDraft(1,body,startup.history(),CheckpointPhase.INPUT_READY)));
        assertEquals(0,fixture.repository.state(l,"checkpoint_agent_state").version());
    }
    @Test void revokedMembershipAndCrossUserMetadataReadAreDenied() throws Exception {
        var c=stoppedInput();denied("RESOURCE_NOT_FOUND",()->fixture.repository.recovery(new Actor(UUID.randomUUID(),fixture.actor.spaceId()),c.lease().taskId()));
        sql("UPDATE pf.membership SET status='REVOKED'");
        denied("RESOURCE_NOT_FOUND",()->fixture.repository.restore(command(c)));
        assertEquals(0,count("SELECT count(*) FROM pf.checkpoint_recovery"));
    }
    @Test void ledgerAdvancedAfterSnapshotCannotBeRestored() {
        var c=stoppedInput();fixture.repository.restore(command(c));
        var target=fixture.repository.claim(fixture.envelope(fixture.repository.task(fixture.actor,c.lease().taskId())),UUID.randomUUID());
        var startup=fixture.repository.executionStartup(target);
        byte[] body=fixture.repository.state(target,"agent_state").body();
        fixture.repository.commitCheckpoint(target,ChatRuntime.bundle(),new CheckpointDraft(0,body,startup.history(),CheckpointPhase.INPUT_READY));
        fixture.repository.beginTurn(target);
        fixture.repository.stopped(target,"BLOCKED","WORKER_INTERRUPTED",null,"2".repeat(64));
        var view=fixture.repository.recovery(fixture.actor,target.taskId());
        var cmd=new RestoreCommand(UUID.randomUUID(),target.taskId(),view.checkpointId(),target.attemptId(),view.taskVersion(),view.revision(),"fixture-operator");
        denied("CHECKPOINT_LEDGER_ADVANCED",()->fixture.repository.restore(cmd));
    }
    @Test void scopedSdkBodyIsCheckedEvenWhenAllHashesAreValid() {
        var l=fixture.lease();var startup=fixture.repository.executionStartup(l);
        byte[] body=("{\"user_id\":\""+UUID.randomUUID()+"\",\"session_id\":\""+l.conversationId()+"\",\"context\":[]}").getBytes(StandardCharsets.UTF_8);
        UUID cp=fixture.repository.commitCheckpoint(l,ChatRuntime.bundle(),new CheckpointDraft(0,body,startup.history(),CheckpointPhase.INPUT_READY));
        fixture.repository.stopped(l,"BLOCKED","WORKER_INTERRUPTED",null,"3".repeat(64));
        var c=new Candidate(l,cp,fixture.repository.recovery(fixture.actor,l.taskId()));
        denied("CHECKPOINT_STATE_SCOPE_MISMATCH",()->fixture.repository.restore(command(c)));
    }
    @Test void queuedRecoveryRevalidatesDeadlineBeforeClaimAndReleasesReservation() throws Exception {
        var c=stoppedInput();fixture.repository.restore(command(c));
        var envelope=fixture.envelope(fixture.repository.task(fixture.actor,c.lease().taskId()));
        sql("UPDATE pf.task SET execution_deadline_at=clock_timestamp()-interval '1 second'");
        assertNull(fixture.repository.claim(envelope,UUID.randomUUID()));
        assertEquals(0,count("SELECT reserved_tokens FROM pf.budget_account"));
        assertEquals("FAILED",fixture.repository.task(fixture.actor,c.lease().taskId()).status());
        assertEquals(1,count("SELECT count(*) FROM pf.attempt"));
    }
    @Test void restoredInputRunsThroughActualSdkAndCommitsOnePhysicalCall() throws Exception {
        var c=stoppedInput();fixture.repository.restore(command(c));
        var target=fixture.repository.claim(fixture.envelope(fixture.repository.task(fixture.actor,c.lease().taskId())),UUID.randomUUID());
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var provider=new com.agentplatform.domain.chat.ChatProvider() {
            public boolean real() {return false;}
            public java.util.concurrent.CompletionStage<Result> stream(String key,List<com.agentplatform.types.harness.HarnessData.Message> messages,java.util.function.Consumer<String> text) {
                calls.incrementAndGet();assertEquals("hello😀",messages.getLast().text());
                assertEquals(1,messages.stream().filter(m->m.role()==Role.USER).count());
                text.accept("restored😀");
                return java.util.concurrent.CompletableFuture.completedFuture(new Result(new Round("restored😀",List.of(),"stop",10,2),true,"local-restore-fixture"));
            }
        };
        try(var execution=new ChatExecution(fixture.repository,provider,fixture.cipher,directory.resolve("sdk"))) {execution.run(target);}
        assertEquals("SUCCEEDED",fixture.repository.task(fixture.actor,target.taskId()).status());
        assertEquals(1,calls.get());assertEquals(1,count("SELECT count(*) FROM pf.model_call"));
        assertEquals(1,count("SELECT count(*) FROM pf.model_turn WHERE status='COMMITTED'"));
        assertEquals(1,count("SELECT count(*) FROM pf.checkpoint_recovery WHERE status='COMPLETED'"));
        assertEquals(0,count("SELECT count(*) FROM pf.execution_slot WHERE task_id IS NOT NULL"));
        assertEquals(2,count("SELECT count(*) FROM pf.message"));
    }
    @Test void apiMetadataRoleCannotReadSdkStateManifestOrCredentialCiphertext() throws Exception {
        var c=stoppedInput();String role="r24_meta_"+UUID.randomUUID().toString().replace("-","");
        sql("CREATE ROLE "+role+" NOLOGIN; GRANT USAGE ON SCHEMA pf TO "+role+"; "+
            "GRANT SELECT ON pf.task,pf.attempt,pf.execution_slot,pf.execution_stop_receipt,pf.model_call,pf.budget_reservation,pf.chat_config_snapshot,pf.model_definition,pf.checkpoint_recovery TO "+role+"; "+
            "GRANT SELECT(id,task_id,attempt_id,bundle_hash,phase,manifest_version,created_at) ON pf.checkpoint TO "+role+"; "+
            "GRANT SELECT(task_id,status) ON pf.tool_operation TO "+role+"; "+
            "GRANT SELECT(id,status,revision) ON pf.credential TO "+role+"; "+
            "GRANT EXECUTE ON FUNCTION pf.authorize_chat_actor(uuid,uuid),pf.authorize_chat_credential(uuid,bigint,uuid,uuid) TO "+role);
        var configuration=new com.zaxxer.hikari.HikariConfig();configuration.setJdbcUrl(url);configuration.setUsername("postgres");configuration.setPassword(password);
        configuration.setConnectionInitSql("SET ROLE "+role);configuration.setMaximumPoolSize(1);configuration.setMinimumIdle(1);
        try(var restricted=new com.zaxxer.hikari.HikariDataSource(configuration)) {
            var api=ChatPersistenceFixture.create(restricted);
            assertTrue(api.recovery(fixture.actor,c.lease().taskId()).eligible());
            for(String query:List.of("SELECT state_json FROM pf.checkpoint","SELECT manifest_json FROM pf.checkpoint","SELECT ciphertext FROM pf.credential","SELECT * FROM pf_runtime.state_slot")) {
                try(var connection=restricted.getConnection();var statement=connection.createStatement()) {
                    var denied=assertThrows(java.sql.SQLException.class,()->statement.executeQuery(query));
                    assertEquals("42501",denied.getSQLState());
                }
            }
        } finally { sql("DROP OWNED BY "+role+"; DROP ROLE "+role); }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void actualSdkResumesPreparedToolButNeverReexecutesCommittedTool(boolean completedTool) throws Exception {
        var repository=fixture.repository;var lease=fixture.lease();repository.executionStartup(lease);
        UUID turn=repository.beginTurn(lease);var physical=fixture.started(lease);
        var tool=new com.agentplatform.types.harness.HarnessData.Call("stable-operation","recovery_probe",Map.of("text","one"),false);
        fixture.commit(lease,turn,fixture.turn("",List.of(tool)),physical);
        var effects=new java.util.concurrent.atomic.AtomicInteger();
        com.agentplatform.domain.harness.HarnessPorts.ToolExecutionPort port=(scope,call)-> {
            effects.incrementAndGet();return java.util.concurrent.CompletableFuture.completedFuture(new Observation(call.id(),call.name(),"fixture:one",OperationStatus.SUCCEEDED));
        };
        if(completedTool) {repository.startTool(lease,tool);effects.incrementAndGet();repository.commitTool(lease,tool,new Observation(tool.id(),tool.name(),"fixture:one",OperationStatus.SUCCEEDED));}
        var codec=new com.agentplatform.infrastructure.harness.agentscope.PlatformMessageCodec();
        var sdk=io.agentscope.core.state.AgentState.builder().userId(lease.userId().toString()).sessionId(lease.conversationId().toString()).build();
        var history=repository.checkpointHistory(lease);
        for(var message:history) {
            var encoded=codec.encode(message);
            if(completedTool)encoded=encoded.withContent(encoded.getContent().stream().map(block->block instanceof io.agentscope.core.message.ToolUseBlock use?
                    (io.agentscope.core.message.ContentBlock)use.withState(io.agentscope.core.message.ToolCallState.FINISHED):block).toList());
            sdk.contextMutable().add(encoded);
        }
        byte[] body=new io.agentscope.core.util.JacksonJsonCodec().toJson(sdk).getBytes(StandardCharsets.UTF_8);
        var checkpoint=repository.commitCheckpoint(lease,ChatRuntime.bundle(),new CheckpointDraft(0,body,history,completedTool?CheckpointPhase.TOOL_COMMITTED:CheckpointPhase.MODEL_COMMITTED));
        repository.stopped(lease,"BLOCKED","WORKER_INTERRUPTED",null,"4".repeat(64));
        var candidate=new Candidate(lease,checkpoint,repository.recovery(fixture.actor,lease.taskId()));repository.restore(command(candidate));
        var target=repository.claim(fixture.envelope(repository.task(fixture.actor,lease.taskId())),UUID.randomUUID());
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var provider=new com.agentplatform.domain.chat.ChatProvider() {
            public boolean real(){return false;}
            public java.util.concurrent.CompletionStage<Result> stream(String key,List<com.agentplatform.types.harness.HarnessData.Message> messages,java.util.function.Consumer<String> text) {
                calls.incrementAndGet();assertTrue(messages.stream().anyMatch(m->!m.observations().isEmpty()));text.accept("done");
                return java.util.concurrent.CompletableFuture.completedFuture(new Result(new Round("done",List.of(),"stop",10,2),true,"fixture-after-tool"));
            }
        };
        var timers=java.util.concurrent.Executors.newScheduledThreadPool(2);
        try(var execution=new ChatExecution(repository,provider,fixture.cipher,directory.resolve("tools"),timers,port)) {execution.run(target);}
        finally {timers.shutdownNow();}
        assertEquals("SUCCEEDED",repository.task(fixture.actor,target.taskId()).status());
        assertEquals(1,effects.get());assertEquals(1,calls.get());
        assertEquals(1,count("SELECT count(*) FROM pf.tool_operation WHERE status='SUCCEEDED'"));
        assertEquals(2,count("SELECT count(*) FROM pf.model_turn WHERE status='COMMITTED'"));
        assertEquals(2,count("SELECT count(*) FROM pf.model_call"));
    }
}
