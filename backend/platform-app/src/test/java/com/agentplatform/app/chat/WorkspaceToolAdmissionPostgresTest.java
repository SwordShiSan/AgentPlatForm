package com.agentplatform.app.chat;

import com.agentplatform.domain.chat.*;
import com.agentplatform.domain.chat.ChatData.*;
import com.agentplatform.domain.harness.HarnessPorts.ToolExchange;
import com.agentplatform.types.TaskPermissionData.Mode;
import com.agentplatform.types.PlanInteractionData.*;
import com.agentplatform.types.harness.HarnessData;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual PG+SDK admission only. The transport is a deterministic port, not a shell/isolation claim. */
class WorkspaceToolAdmissionPostgresTest extends WorkspaceToolsMigrationPostgresTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path work;
    @org.junit.jupiter.api.AfterEach void releaseFixtureOwnership()throws Exception {
        sql("UPDATE pf.execution_slot SET task_id=NULL,owner_id=NULL,lease_until=NULL,fencing_token=fencing_token+1 WHERE task_id IN(SELECT id FROM pf.task WHERE space_id='"+actor.spaceId()+"' AND creator_id='"+actor.userId()+"')");
        sql("UPDATE pf.workspace_sandbox_slot SET space_id=NULL,workspace_id=NULL,environment_id=NULL,generation=NULL,revision=revision+1 WHERE workspace_id='"+workspace+"'");
    }
    private Lease claim(Task task){
        UUID relay=UUID.randomUUID();var publication=repository.publications(relay).stream().filter(p->p.envelope().aggregateId().equals(task.id())).findFirst().orElseThrow();
        repository.published(relay,publication,true);var lease=repository.claim(publication.envelope(),UUID.randomUUID());assertNotNull(lease);return lease;
    }
    private Task enabled(Mode mode){
        repository=ChatPersistenceFixture.create(pool,true);
        return repository.firstAuthorized(actor,UUID.randomUUID(),"R44 policy probe",UUID.fromString("0199e000-0000-7000-8000-000000000002"),"PLATFORM","Exercise durable admission, not a physical command",false,workspace,mode).task();
    }
    private ChatProvider provider(AtomicInteger calls){return new ChatProvider(){
        public boolean real(){return false;}
        public CompletionStage<Result> stream(String key,List<HarnessData.Message> m,java.util.function.Consumer<String> text){throw new AssertionError("Typed provider required");}
        public Exchange openTyped(String model,String key,List<HarnessData.Message> m,List<HarnessData.ToolDefinition> definitions,java.util.function.Consumer<String> text){
            assertTrue(definitions.stream().anyMatch(d->d.name().equals("workspace_shell")));int index=calls.getAndIncrement();assertTrue(index<2,"No replacement model call is allowed on approval resume");
            var round=index==0?new HarnessData.Round("",List.of(new HarnessData.Call("shell-one","workspace_shell",Map.of("command","printf 666","cwd","","timeoutSeconds",20,"outputs",List.of()),false)),"tool_calls",12,8)
                    :new HarnessData.Round("Deterministic admission probe finished; no physical shell was run.",List.of(),"stop",12,8);
            if(!round.text().isEmpty())text.accept(round.text());
            return new Exchange(){public CompletionStage<Result> result(){return CompletableFuture.completedFuture(new Result(round,true,"r44-admission-"+index));}public CompletionStage<Void> terminated(){return CompletableFuture.completedFuture(null);}public void cancel(){}};
        }
    };}
    private void run(Lease lease,ChatProvider provider,AtomicInteger admissions){run(lease,provider,admissions,false);}
    private void run(Lease lease,ChatProvider provider,AtomicInteger admissions,boolean production){
        try(var timers=Executors.newScheduledThreadPool(2);var execution=new ChatExecution(repository,provider,new com.agentplatform.infrastructure.chat.CredentialCipher(new byte[32]),work,timers)){
            WorkspaceToolRuntime selected=new WorkspaceToolRuntime(){
                public List<HarnessData.ToolDefinition> definitions(Lease l){return WorkspaceTools.DEFINITIONS;}
                public void prepare(Lease l,List<HarnessData.Call> calls){repository.prepareWorkspaceTools(l,calls,"sha256:"+"a".repeat(64),"b".repeat(64));}
                public void admit(Lease l,HarnessData.Call call){repository.startTool(l,call);}
                public ToolExchange open(Lease l,HarnessData.Call call){
                    assertNotNull(repository.workspaceToolIntent(l,call));admissions.incrementAndGet();
                    return new ToolExchange(){public CompletionStage<HarnessData.Observation> result(){return CompletableFuture.completedFuture(new HarnessData.Observation(call.id(),call.name(),"Admission confirmed; deterministic transport only",HarnessData.OperationStatus.SUCCEEDED));}public CompletionStage<Void> terminated(){return CompletableFuture.completedFuture(null);}public void cancel(){}};
                }
            };
            try(var io=Executors.newSingleThreadExecutor()){execution.configureWorkspaceTools(production?productionComposition(lease,admissions,io,timers):selected);execution.run(lease);}
        }
    }
    @Test void fullModeAdmitsOneOriginalShellWithoutAnApprovalInteraction() throws Exception {
        var task=enabled(Mode.FULL);var calls=new AtomicInteger();var admissions=new AtomicInteger();run(claim(task),provider(calls),admissions);
        assertEquals("SUCCEEDED",repository.task(actor,task.id()).status());assertEquals(1,admissions.get());assertEquals(2,calls.get());
        assertEquals("0",scalar("SELECT count(*) FROM pf.task_interaction WHERE task_id='"+task.id()+"'"));
        assertEquals("SUCCEEDED",scalar("SELECT status FROM pf.tool_operation WHERE task_id='"+task.id()+"'"));
    }
    @Test void riskModeAsksThenResumesTheOriginalCallWithoutAnotherModelRequest() throws Exception {
        var task=enabled(Mode.RISK_BASED);var calls=new AtomicInteger();var admissions=new AtomicInteger();var provider=provider(calls);
        run(claim(task),provider,admissions);assertEquals("WAITING_INPUT",repository.task(actor,task.id()).status());assertEquals(0,admissions.get());
        var pending=repository.pending(actor,task.conversationId()).getFirst();assertEquals(Kind.TOOL_APPROVAL,pending.kind());
        assertNotNull(pending.approval().review());assertEquals("SHELL",pending.approval().review().kind());
        assertEquals("printf 666",pending.approval().review().command());assertEquals("",pending.approval().review().cwd());
        assertEquals("ENTIRE_BOUND_WORKSPACE",pending.approval().review().impact());assertEquals("NONE",pending.approval().review().network());
        assertTrue(pending.approval().review().stopsAfterEachCall());assertEquals(List.of(),pending.approval().review().outputs());
        repository.answer(actor,task.id(),pending.interactionId(),UUID.randomUUID(),new Answer(pending.version(),Decision.ALLOW_ONCE,"",List.of()));
        run(claim(task),provider,admissions);assertEquals("SUCCEEDED",repository.task(actor,task.id()).status());assertEquals(1,admissions.get());assertEquals(2,calls.get());
        assertEquals("1",scalar("SELECT count(*) FROM pf.tool_approval_consumption WHERE task_id='"+task.id()+"'"));
    }
    @Test void activationAfterApprovalUsesTheLiveAttemptRatherThanTheFirstHistoricalBinding() throws Exception {
        var task=enabled(Mode.RISK_BASED);var calls=new AtomicInteger();var admissions=new AtomicInteger();
        run(claim(task),provider(calls),admissions);
        var pending=repository.pending(actor,task.conversationId()).getFirst();
        repository.answer(actor,task.id(),pending.interactionId(),UUID.randomUUID(),new Answer(pending.version(),Decision.ALLOW_ONCE,"",List.of()));
        var current=claim(task);
        var call=new HarnessData.Call("shell-one","workspace_shell",Map.of("command","printf 666","cwd","","timeoutSeconds",20,"outputs",List.of()),false);
        repository.startTool(current,call);
        var intent=repository.workspaceToolIntent(current,call);
        UUID environment=UUID.randomUUID(),activation=UUID.randomUUID(),command=UUID.randomUUID();
        sql("BEGIN;INSERT INTO pf.workspace_environment(id,space_id,workspace_id,generation,fence,state,image_id,policy_hash,creation_command_id,creation_fence,operation_id) VALUES('"+environment+"','"+actor.spaceId()+"','"+workspace+"',1,1,'PREPARING','sha256:"+"a".repeat(64)+"','"+"b".repeat(64)+"','"+command+"',1,'"+activation+"');"
            +"INSERT INTO pf.workspace_environment_operation(id,space_id,workspace_id,environment_id,kind,initiator,actor_id,command_id,generation,fence,status,effect_state) VALUES('"+activation+"','"+actor.spaceId()+"','"+workspace+"','"+environment+"','PREPARE','USER','"+actor.userId()+"','"+command+"',1,1,'PENDING','NOT_STARTED');"
            +"INSERT INTO pf.workspace_tool_job_binding(id,operation_id,space_id,task_id,workspace_id,environment_id,activation_operation_id) VALUES('"+UUID.randomUUID()+"','"+intent.operationId()+"','"+actor.spaceId()+"','"+task.id()+"','"+workspace+"','"+environment+"','"+activation+"');COMMIT;");
        assertEquals("2",scalar("SELECT count(*) FROM pf.operation_execution_binding WHERE operation_id='"+intent.operationId()+"'"));
        assertEquals("t",scalar("SELECT pf.workspace_tool_activation_allowed('"+activation+"',false)"));
        sql("UPDATE pf.execution_dispatch SET lease_until=clock_timestamp()-interval '1 second' WHERE task_id='"+task.id()+"'");
        assertEquals("f",scalar("SELECT pf.workspace_tool_activation_allowed('"+activation+"',false)"));
        assertEquals(0,admissions.get());
    }
    @Test void contentChangeInvalidatesThePendingCommandButNotItsImmutableIntent() throws Exception {
        var task=enabled(Mode.RISK_BASED);var calls=new AtomicInteger();var admissions=new AtomicInteger();run(claim(task),provider(calls),admissions);
        var pending=repository.pending(actor,task.conversationId()).getFirst();
        sql("UPDATE pf.workspace SET content_revision=content_revision+1 WHERE id='"+workspace+"'");
        assertEquals(412,assertThrows(ChatFailure.class,()->repository.answer(actor,task.id(),pending.interactionId(),UUID.randomUUID(),new Answer(pending.version(),Decision.ALLOW_ONCE,"",List.of()))).status());
        assertEquals(0,admissions.get());assertTrue(repository.pending(actor,task.conversationId()).getFirst().approval().stale());
    }
    private WorkspaceToolRuntime productionComposition(Lease lease,AtomicInteger executions,ExecutorService io,ScheduledExecutorService timers) {
        try {
            UUID environment=UUID.randomUUID(),operation=UUID.randomUUID(),command=UUID.randomUUID();
            if("0".equals(scalar("SELECT count(*) FROM pf.workspace_environment WHERE workspace_id='"+workspace+"'"))) {
                sql("BEGIN;INSERT INTO pf.workspace_environment(id,space_id,workspace_id,generation,fence,state,image_id,policy_hash,creation_command_id,creation_fence,operation_id,container_id,container_started_at,readiness_hash) VALUES('"+environment+"','"+actor.spaceId()+"','"+workspace+"',1,1,'READY','sha256:"+"a".repeat(64)+"','"+"b".repeat(64)+"','"+command+"',1,'"+operation+"','"+"c".repeat(64)+"','2026-10-27T00:00:00Z','"+"d".repeat(64)+"');"
                    +"INSERT INTO pf.workspace_environment_operation(id,space_id,workspace_id,environment_id,kind,initiator,actor_id,command_id,generation,fence,status,effect_state,owner_id,lease_until,deadline_at) VALUES('"+operation+"','"+actor.spaceId()+"','"+workspace+"','"+environment+"','PREPARE','USER','"+actor.userId()+"','"+command+"',1,1,'COMMITTED','READY','"+UUID.randomUUID()+"',clock_timestamp()+interval '60 seconds',clock_timestamp()+interval '120 seconds');"
                    +"UPDATE pf.workspace_sandbox_slot SET space_id='"+actor.spaceId()+"',workspace_id='"+workspace+"',environment_id='"+environment+"',generation=1,revision=revision+1 WHERE id=1;COMMIT;");
            }
            var factory=new org.mybatis.spring.SqlSessionFactoryBean();factory.setDataSource(pool);
            var config=new org.apache.ibatis.session.Configuration();config.setMapUnderscoreToCamelCase(true);config.setCacheEnabled(false);config.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
            config.getTypeHandlerRegistry().register(new com.agentplatform.infrastructure.dao.typehandler.UuidTypeHandler());factory.setConfiguration(config);
            factory.setMapperLocations(new org.springframework.core.io.support.PathMatchingResourcePatternResolver().getResources("classpath*:mybatis/mapper/**/*.xml"));
            var session=new org.mybatis.spring.SqlSessionTemplate(factory.getObject());var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.support.JdbcTransactionManager(pool));
            var uses=new com.agentplatform.app.workspace.TransactionalEnvironmentUseRepository(new com.agentplatform.infrastructure.adapter.repository.workspace.MyBatisEnvironmentUseRepository(
                session.getMapper(com.agentplatform.infrastructure.dao.workspace.EnvironmentUseMapper.class),session.getMapper(com.agentplatform.infrastructure.dao.workspace.EnvironmentPreparationMapper.class),
                session.getMapper(com.agentplatform.infrastructure.dao.workspace.EnvironmentExecutionMapper.class),session.getMapper(com.agentplatform.infrastructure.dao.workspace.WorkspaceMapper.class)),tx);
            var jobs=new com.agentplatform.infrastructure.adapter.repository.chat.MyBatisWorkspaceToolJobs(session.getMapper(com.agentplatform.infrastructure.dao.chat.WorkspaceToolJobMapper.class));
            var files=new com.agentplatform.app.files.TransactionalFileRepository(new com.agentplatform.infrastructure.adapter.repository.files.MyBatisFileRepository(
                session.getMapper(com.agentplatform.infrastructure.dao.files.FileMapper.class),session.getMapper(com.agentplatform.infrastructure.dao.workspace.WorkspaceMapper.class),
                session.getMapper(com.agentplatform.infrastructure.dao.workspace.EnvironmentPreparationMapper.class),"sha256:"+"a".repeat(64),"b".repeat(64),session.getMapper(com.agentplatform.infrastructure.dao.files.FileCaptureMapper.class)),tx);
            var instance=new com.agentplatform.domain.workspace.RunnerInstance(UUID.randomUUID(),com.agentplatform.infrastructure.workspace.RunnerSignatures.sha256(UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),"sha256:"+"f".repeat(64),"2026-10-28T00:00:00Z");
            tx.executeWithoutResult(status->session.getMapper(com.agentplatform.infrastructure.dao.workspace.EnvironmentExecutionMapper.class).registerRunner(instance));
            var lifecycle=org.mockito.Mockito.mock(com.agentplatform.domain.workspace.EnvironmentLifecycleRepository.class);
            org.mockito.Mockito.when(lifecycle.get(new com.agentplatform.domain.workspace.WorkspaceData.Actor(lease.userId(),lease.spaceId()),workspace)).thenReturn(
                new com.agentplatform.domain.workspace.EnvironmentPreparationRepository.Snapshot(environment,workspace,"READY",1,1,operation,"PREPARE","COMMITTED",null,false,false));
            var wire=new com.agentplatform.domain.workspace.WorkspaceShellRunnerPort(){
                public com.agentplatform.domain.workspace.EnvironmentUseData.Receipt executeShell(com.agentplatform.domain.workspace.EnvironmentUseData.Command cmd){
                    executions.incrementAndGet();assertTrue(uses.accept(cmd,instance.id()));String exec=com.agentplatform.infrastructure.workspace.RunnerSignatures.sha256(cmd.id().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));uses.recordExec(cmd,instance.id(),cmd.containerId(),exec);
                    var stop=new com.agentplatform.domain.workspace.SandboxRuntime.Stop(cmd.containerId(),cmd.imageId(),cmd.containerStartedAt(),java.time.Instant.now().minusMillis(10).toString(),true,true,java.time.Instant.now());
                    uses.commitFact(cmd,instance.id(),new com.agentplatform.domain.workspace.EnvironmentUseData.Fact(cmd.id(),cmd.environmentId(),cmd.generation(),cmd.fence(),cmd.requestHash(),instance.id(),"KNOWN",
                        new com.agentplatform.domain.workspace.SandboxRuntime.Output(exec,0,"666","",false,false,false,stop),stop,null));return uses.receipt(cmd);
                }
                public com.agentplatform.domain.workspace.EnvironmentUseData.Receipt queryShell(com.agentplatform.domain.workspace.EnvironmentUseData.Command cmd){return uses.receipt(cmd);}
            };
            var assets=new com.agentplatform.domain.files.AssetStore(){
                public Identity put(String key,long size,String hash,java.io.InputStream stream){try{assertEquals(size,stream.readAllBytes().length);return new Identity(key,size,hash);}catch(java.io.IOException error){throw new IllegalStateException(error);}}
                public Content open(String key,Long first,Long last){throw new AssertionError("No download in this deterministic composition probe");}
                public Identity inspect(String key){throw new AssertionError("No inspect");}public void deleteUncommitted(String key){throw new AssertionError("No delete");}
            };
            return new WorkspaceToolsApplicationService(repository,files,assets,lifecycle,uses,wire,jobs,tx,io,timers,"sha256:"+"a".repeat(64),"b".repeat(64));
        }catch(Exception failure){throw new IllegalStateException(failure);}
    }
    @Test void sdkFullUsesTheProductionCompositionAndReturnsToTheSameTask()throws Exception {
        var task=enabled(Mode.FULL);var calls=new AtomicInteger();var effects=new AtomicInteger();run(claim(task),provider(calls),effects,true);
        assertEquals("SUCCEEDED",repository.task(actor,task.id()).status());assertEquals(1,effects.get());assertEquals(2,calls.get());
        assertEquals("3",scalar("SELECT committed_bytes FROM pf.space_storage WHERE space_id='"+actor.spaceId()+"'"));
    }
    @Test void sdkApprovalResumesExactOriginalOperationIntoTheProductionComposition()throws Exception {
        var task=enabled(Mode.RISK_BASED);var calls=new AtomicInteger();var effects=new AtomicInteger();var provider=provider(calls);
        run(claim(task),provider,effects,true);assertEquals("WAITING_INPUT",repository.task(actor,task.id()).status());assertEquals(0,effects.get());
        var pending=repository.pending(actor,task.conversationId()).getFirst();
        repository.answer(actor,task.id(),pending.interactionId(),UUID.randomUUID(),new Answer(pending.version(),Decision.ALLOW_ONCE,"",List.of()));
        run(claim(task),provider,effects,true);assertEquals("SUCCEEDED",repository.task(actor,task.id()).status());assertEquals(1,effects.get());assertEquals(2,calls.get());
        assertEquals("1",scalar("SELECT count(*) FROM pf.workspace_tool_shell_admission WHERE task_id='"+task.id()+"'"));
    }

}
