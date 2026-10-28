package com.agentplatform.app.chat;

import com.agentplatform.app.workspace.TransactionalEnvironmentUseRepository;
import com.agentplatform.domain.chat.*;
import com.agentplatform.domain.chat.ChatData.Lease;
import com.agentplatform.domain.workspace.*;
import com.agentplatform.infrastructure.adapter.repository.workspace.MyBatisEnvironmentUseRepository;
import com.agentplatform.infrastructure.dao.typehandler.UuidTypeHandler;
import com.agentplatform.infrastructure.dao.workspace.*;
import com.agentplatform.types.TaskPermissionData.Mode;
import com.agentplatform.types.harness.HarnessData.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.*;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Actual PG/Mapper child ownership, not a native execution or runner receipt substitute. */
class WorkspaceShellAdmissionPostgresTest extends WorkspaceToolsMigrationPostgresTest {
    private EnvironmentUseRepository uses;private Lease current;private Call tool;
    private SqlSessionTemplate mapperSession;private TransactionTemplate transactions;
    private WorkspaceData.Actor workspaceActor(){return new WorkspaceData.Actor(actor.userId(),actor.spaceId());}
    private WorkspaceShellData.Request prepared() throws Exception {
        return prepared(List.of(),null);
    }
    private WorkspaceShellData.Request prepared(List<Map<String,Object>> outputs,Long limit) throws Exception {
        UUID environment=UUID.randomUUID(),operation=UUID.randomUUID(),command=UUID.randomUUID();
        sql("BEGIN;INSERT INTO pf.workspace_environment(id,space_id,workspace_id,generation,fence,state,image_id,policy_hash,creation_command_id,creation_fence,operation_id,container_id,container_started_at,readiness_hash) VALUES('"+environment+"','"+actor.spaceId()+"','"+workspace+"',1,1,'READY','sha256:"+"a".repeat(64)+"','"+"b".repeat(64)+"','"+command+"',1,'"+operation+"','"+"c".repeat(64)+"','2026-10-27T00:00:00Z','"+"d".repeat(64)+"');"
            +"INSERT INTO pf.workspace_environment_operation(id,space_id,workspace_id,environment_id,kind,initiator,actor_id,command_id,generation,fence,status,effect_state,owner_id,lease_until,deadline_at) VALUES('"+operation+"','"+actor.spaceId()+"','"+workspace+"','"+environment+"','PREPARE','USER','"+actor.userId()+"','"+command+"',1,1,'COMMITTED','READY','"+UUID.randomUUID()+"',clock_timestamp()+interval '60 seconds',clock_timestamp()+interval '120 seconds');"
            +"UPDATE pf.workspace_sandbox_slot SET space_id='"+actor.spaceId()+"',workspace_id='"+workspace+"',environment_id='"+environment+"',generation=1,revision=revision+1 WHERE id=1;COMMIT;");
        var factory=new SqlSessionFactoryBean();factory.setDataSource(pool);var configuration=new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);configuration.setCacheEnabled(false);configuration.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);configuration.getTypeHandlerRegistry().register(new UuidTypeHandler());
        factory.setConfiguration(configuration);factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mybatis/mapper/**/*.xml"));
        var session=new SqlSessionTemplate(factory.getObject());mapperSession=session;transactions=new TransactionTemplate(new JdbcTransactionManager(pool));
        uses=new TransactionalEnvironmentUseRepository(new MyBatisEnvironmentUseRepository(session.getMapper(EnvironmentUseMapper.class),
            session.getMapper(EnvironmentPreparationMapper.class),session.getMapper(EnvironmentExecutionMapper.class),session.getMapper(WorkspaceMapper.class)),transactions);
        repository=ChatPersistenceFixture.create(pool,true);
        var task=repository.firstAuthorized(actor,UUID.randomUUID(),"R44 shell parent",UUID.fromString("0199e000-0000-7000-8000-000000000002"),"PLATFORM","shell admission",false,workspace,Mode.FULL).task();
        var publication=repository.publications(UUID.randomUUID()).stream().filter(p->p.envelope().aggregateId().equals(task.id())).findFirst().orElseThrow();
        current=repository.claim(publication.envelope(),UUID.randomUUID());assertNotNull(current);
        tool=new Call("shell-owned","workspace_shell",Map.of("command","printf 666","cwd","","timeoutSeconds",20,"outputs",outputs),false);
        var turn=repository.beginTurn(current);var call=repository.beginCall(current,false);repository.startCall(current,call,false);
        repository.commitRound(current,turn,new Turn(UUID.randomUUID().toString(),1,new Round("",List.of(tool),"tool_calls",10,2)),new ExecutionLedger.CallResult(call,true,"r44-shell-metadata",false));
        repository.prepareWorkspaceTools(current,List.of(tool),"sha256:"+"a".repeat(64),"b".repeat(64));
        if(limit!=null)sql("UPDATE pf.space_storage SET object_limit_bytes="+limit+" WHERE space_id='"+actor.spaceId()+"'");
        repository.startTool(current,tool);
        return repository.workspaceShellRequest(current,tool);
    }
    @AfterEach void retireMetadataFixtures() throws Exception {
        if(current!=null)sql("UPDATE pf.execution_slot SET task_id=NULL,owner_id=NULL,lease_until=NULL,fencing_token=fencing_token+1 WHERE task_id='"+current.taskId()+"' AND owner_id='"+current.ownerId()+"'");
        sql("UPDATE pf.workspace_sandbox_slot SET space_id=NULL,workspace_id=NULL,environment_id=NULL,generation=NULL,revision=revision+1 WHERE workspace_id='"+workspace+"'");
    }
    @Test void shellUseIsUniquelyLinkedToTheOriginalTaskAndEnvironmentFenceIsIndependent() throws Exception {
        var request=prepared();UUID id=request.parent().operationId();
        var admitted=uses.admitShell(workspaceActor(),workspace,id,current.ownerId(),request);assertTrue(admitted.fresh());assertEquals("SHELL",admitted.command().kind());
        assertEquals(request,admitted.command().request());assertEquals(current.fence(),request.parent().taskFence());assertEquals(2,admitted.command().fence());
        var repeated=uses.admitShell(workspaceActor(),workspace,id,current.ownerId(),request);assertFalse(repeated.fresh());assertEquals(admitted.command(),repeated.command());
        assertEquals("1",scalar("SELECT count(*) FROM pf.workspace_tool_shell_admission WHERE task_id='"+current.taskId()+"'"));
        assertEquals("1",scalar("SELECT count(*) FROM pf.workspace_tool_job_binding WHERE task_id='"+current.taskId()+"'"));
        uses.renew(admitted.command());repository.cancel(actor,current.taskId(),UUID.randomUUID());
        assertThrows(WorkspaceFailure.class,()->uses.checkOwnership(admitted.command()));
        assertEquals("1",scalar("SELECT count(*) FROM pf.workspace_sandbox_slot WHERE workspace_id='"+workspace+"'"));
    }
    @Test void changedCommandCannotReuseAValidParentApprovalAndAdmissionHasNoPartialRows() throws Exception {
        var request=prepared();var altered=new WorkspaceShellData.Request(WorkspaceShellData.PROTOCOL,request.parent(),new SandboxRuntime.ShellRequest("printf different","",20));
        assertEquals("SHELL_PARENT_NOT_AUTHORIZED",assertThrows(WorkspaceFailure.class,()->uses.admitShell(workspaceActor(),workspace,request.parent().operationId(),current.ownerId(),altered)).code());
        assertEquals("0",scalar("SELECT count(*) FROM pf.workspace_environment_use WHERE workspace_id='"+workspace+"'"));
        assertEquals("1",scalar("SELECT fence FROM pf.workspace_environment WHERE workspace_id='"+workspace+"'"));
    }
    @Test void expiredTaskDeadlineCannotBeExtendedByASeparateShellUseLease() throws Exception {
        var request=prepared();sql("UPDATE pf.task SET execution_deadline_at=clock_timestamp()-interval '1 second' WHERE id='"+current.taskId()+"'");
        assertThrows(WorkspaceFailure.class,()->uses.admitShell(workspaceActor(),workspace,request.parent().operationId(),current.ownerId(),request));
        assertEquals("0",scalar("SELECT count(*) FROM pf.workspace_environment_use WHERE workspace_id='"+workspace+"'"));
    }
    @Test void exactStopRequestRemainsWritableAfterCancellationButIsNotAStopReceipt() throws Exception {
        var request=prepared();var admitted=uses.admitShell(workspaceActor(),workspace,request.parent().operationId(),current.ownerId(),request);
        var forged=new Lease(current.taskId(),current.spaceId(),current.userId(),current.conversationId(),current.attemptId(),UUID.randomUUID(),
                current.fence(),current.modelId(),current.credentialId(),current.credentialRevision(),current.assistantId());
        assertThrows(ChatFailure.class,()->repository.requestWorkspaceToolStop(forged,tool,WorkspaceToolLedger.StopReason.CANCEL));
        repository.cancel(actor,current.taskId(),UUID.randomUUID());
        repository.requestWorkspaceToolStop(current,tool,WorkspaceToolLedger.StopReason.CANCEL);
        repository.requestWorkspaceToolStop(current,tool,WorkspaceToolLedger.StopReason.TIMEOUT);
        assertEquals("1",scalar("SELECT count(*) FROM pf.workspace_tool_stop_request WHERE operation_id='"+request.parent().operationId()+"'"));
        assertEquals("CANCEL",scalar("SELECT reason_code FROM pf.workspace_tool_stop_request WHERE operation_id='"+request.parent().operationId()+"'"));
        assertThrows(WorkspaceFailure.class,()->uses.checkOwnership(admitted.command()));assertFalse(uses.stopCommitted(admitted.command().id()));
        assertEquals("1",scalar("SELECT count(*) FROM pf.workspace_sandbox_slot WHERE workspace_id='"+workspace+"'"));
    }
    @Test void shellReservesDeclaredOutputsAndResultOnceWithoutInventingUnknownObjectContent() throws Exception {
        var request=prepared(List.of(Map.of("path","first.txt","maxBytes",4096L),Map.of("path","second.txt","maxBytes",8192L)),null);
        String scope=" WHERE space_id='"+actor.spaceId()+"'";
        assertEquals("77824",scalar("SELECT reserved_bytes FROM pf.space_storage"+scope));
        assertEquals("2",scalar("SELECT count(*) FROM pf.tool_output_capture"+scope));
        assertEquals("1",scalar("SELECT count(*) FROM pf.tool_result_reservation"+scope));
        assertEquals("0",scalar("SELECT count(*) FROM pf.asset_object"+scope));
        assertThrows(ChatFailure.class,()->repository.startTool(current,tool));
        assertEquals("77824",scalar("SELECT reserved_bytes FROM pf.space_storage"+scope));
        assertThrows(java.sql.SQLException.class,()->sql("UPDATE pf.tool_output_capture SET relative_path='different.txt' WHERE operation_id='"+request.parent().operationId()+"'"));
        repository.cancel(actor,current.taskId(),UUID.randomUUID());
        assertEquals("77824",scalar("SELECT reserved_bytes FROM pf.space_storage"+scope));
    }
    @Test void quotaRejectionRollsBackToolStartAndAllOutputReservations() throws Exception {
        var denied=assertThrows(ChatFailure.class,()->prepared(List.of(Map.of("path","result.txt","maxBytes",4096L)),69631L));
        assertEquals("OBJECT_QUOTA_EXCEEDED",denied.code());
        String scope=" WHERE space_id='"+actor.spaceId()+"'";
        assertEquals("0",scalar("SELECT reserved_bytes FROM pf.space_storage"+scope));
        assertEquals("0",scalar("SELECT count(*) FROM pf.tool_output_capture"+scope));
        assertEquals("0",scalar("SELECT count(*) FROM pf.tool_result_reservation"+scope));
        assertEquals("PREPARED",scalar("SELECT status FROM pf.tool_operation WHERE task_id='"+current.taskId()+"'"));
    }
    private com.agentplatform.domain.files.FileRepository captureFiles() {
        return new com.agentplatform.app.files.TransactionalFileRepository(new com.agentplatform.infrastructure.adapter.repository.files.MyBatisFileRepository(
            mapperSession.getMapper(com.agentplatform.infrastructure.dao.files.FileMapper.class),mapperSession.getMapper(WorkspaceMapper.class),
            mapperSession.getMapper(EnvironmentPreparationMapper.class),"sha256:"+"a".repeat(64),"b".repeat(64),
            mapperSession.getMapper(com.agentplatform.infrastructure.dao.files.FileCaptureMapper.class)),transactions);
    }
    private record StoppedSource(EnvironmentUseData.Command command,RunnerInstance runner) {}
    private StoppedSource stoppedSource()throws Exception {
        var request=prepared(List.of(Map.of("path","result.txt","maxBytes",32L)),null);
        var use=uses.admitShell(workspaceActor(),workspace,request.parent().operationId(),current.ownerId(),request).command();
        var runner=new RunnerInstance(UUID.randomUUID(),randomHash(),"sha256:"+"6".repeat(64),"2026-10-28T00:00:00Z");
        transactions.executeWithoutResult(status->mapperSession.getMapper(EnvironmentExecutionMapper.class).registerRunner(runner));
        assertTrue(uses.accept(use,runner.id()));String exec=randomHash();uses.recordExec(use,runner.id(),use.containerId(),exec);
        var stop=new SandboxRuntime.Stop(use.containerId(),use.imageId(),use.containerStartedAt(),java.time.Instant.now().minusMillis(100).toString(),true,true,java.time.Instant.now());
        uses.commitFact(use,runner.id(),new EnvironmentUseData.Fact(use.id(),use.environmentId(),use.generation(),use.fence(),use.requestHash(),runner.id(),"KNOWN",
            new SandboxRuntime.Output(exec,0,"666","",false,false,false,stop),stop,null));
        return new StoppedSource(use,runner);
    }
    private static String randomHash(){return com.agentplatform.infrastructure.workspace.RunnerSignatures.sha256(UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    @Test void capturesCannotQueueBeforeTheOriginalShellHasACommittedWholeStop()throws Exception {
        var request=prepared(List.of(Map.of("path","result.txt","maxBytes",32L)),null);
        uses.admitShell(workspaceActor(),workspace,request.parent().operationId(),current.ownerId(),request);
        assertThrows(com.agentplatform.domain.files.FileFailure.class,()->captureFiles().admitCaptures(new com.agentplatform.domain.files.FileData.Actor(actor.userId(),actor.spaceId()),request.parent().operationId()));
        assertEquals("0",scalar("SELECT count(*) FROM pf.file_operation WHERE workspace_id='"+workspace+"'"));
    }
    @Test void signedCaptureJobPublishesOneImmutableVersionAndSettlesOnlyItsOriginalReservationDespiteResponseLoss()throws Exception {
        var source=stoppedSource();var files=captureFiles();var fileActor=new com.agentplatform.domain.files.FileData.Actor(actor.userId(),actor.spaceId());
        UUID sourceOperation=repository.workspaceToolIntent(current,tool).operationId();var targets=files.admitCaptures(fileActor,sourceOperation);
        assertEquals(targets,files.admitCaptures(fileActor,sourceOperation));assertEquals(1,targets.size());var target=targets.getFirst();
        var publication=files.publications(UUID.randomUUID()).stream().filter(p->p.operationId().equals(target.fileOperationId())).findFirst().orElseThrow();
        var command=files.claim(publication,UUID.randomUUID());assertNotNull(command);
        var runnerRepo=new com.agentplatform.app.files.TransactionalFileRunnerRepository(new com.agentplatform.infrastructure.adapter.repository.files.MyBatisFileRunnerRepository(
            mapperSession.getMapper(com.agentplatform.infrastructure.dao.files.FileRunnerMapper.class),mapperSession.getMapper(com.agentplatform.infrastructure.dao.files.FileMapper.class),
            mapperSession.getMapper(WorkspaceMapper.class),mapperSession.getMapper(EnvironmentPreparationMapper.class),mapperSession.getMapper(EnvironmentExecutionMapper.class),
            mapperSession.getMapper(com.agentplatform.infrastructure.dao.files.FileTransferMapper.class),mapperSession.getMapper(com.agentplatform.infrastructure.dao.files.FileCaptureMapper.class)),transactions);
        var runtime=org.mockito.Mockito.mock(com.agentplatform.domain.files.FileRuntime.class);
        org.mockito.Mockito.when(runtime.runnerInstance()).thenReturn(source.runner());org.mockito.Mockito.when(runtime.policyHash()).thenReturn(command.operation().helperPolicyHash());
        byte[] bytes="666".getBytes(java.nio.charset.StandardCharsets.UTF_8);String hash=com.agentplatform.infrastructure.workspace.RunnerSignatures.sha256(bytes);
        var objects=new java.util.concurrent.ConcurrentHashMap<String,byte[]>();
        var assets=new com.agentplatform.domain.files.AssetStore(){
            public Identity put(String key,long length,String sha,java.io.InputStream input){try{var value=input.readAllBytes();assertEquals(length,value.length);assertEquals(sha,com.agentplatform.infrastructure.workspace.RunnerSignatures.sha256(value));objects.put(key,value);return new Identity(key,length,sha);}catch(java.io.IOException e){throw new IllegalStateException(e);}}
            public Content open(String key,Long first,Long last){var value=Objects.requireNonNull(objects.get(key));return new Content(new java.io.ByteArrayInputStream(value),value.length,value.length);}
            public Identity inspect(String key){var value=objects.get(key);return new Identity(key,value.length,com.agentplatform.infrastructure.workspace.RunnerSignatures.sha256(value));}
            public void deleteUncommitted(String key){throw new AssertionError("No cleanup/replay in capture probe");}
        };
        var json=tools.jackson.databind.json.JsonMapper.builder().build();
        org.mockito.Mockito.when(runtime.capture(org.mockito.ArgumentMatchers.eq(command),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenAnswer(invocation->{
            var authority=invocation.getArgument(3,com.agentplatform.domain.files.FileRuntime.Authority.class);authority.check();authority.created(randomHash());authority.startRequested();authority.started("2026-10-28T00:00:00Z");authority.exec(randomHash());
            var sink=invocation.getArgument(2,com.agentplatform.domain.files.FileRuntime.CaptureSink.class);sink.described(bytes.length,hash);
            var physical=authority.physical();var stop=new com.agentplatform.domain.files.FileRuntime.Stop(command.operation().commandId(),physical.containerId(),command.operation().helperImageId(),physical.startedAt(),physical.execId(),java.time.Instant.now().minusMillis(10).toString(),false,true,true,java.time.Instant.now());
            sink.stopped(stop);sink.store(bytes.length,hash,new java.io.ByteArrayInputStream(bytes));
            return new com.agentplatform.domain.files.FileRuntime.Result("CAPTURED",json.writeValueAsString(Map.of("protocol","file-capture-v1","operationId",command.operation().id(),"state","CAPTURED","bytes",bytes.length,"sha256",hash)),json.writeValueAsString(stop));
        });
        var keyA=new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(3072).keyID("capture-control").generate();var keyB=new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(3072).keyID("capture-runner").generate();
        var control=new com.agentplatform.infrastructure.workspace.RunnerSignatures(keyA,keyB.toPublicJWK(),"control","runner",java.time.Clock.systemUTC());
        var signed=new com.agentplatform.app.files.FileCaptureRunnerService(runnerRepo,runtime,assets,new com.agentplatform.infrastructure.workspace.RunnerSignatures(keyB,keyA.toPublicJWK(),"runner","control",java.time.Clock.systemUTC()));
        var transport=new com.agentplatform.domain.files.FileRunnerPort(){
            public com.agentplatform.domain.files.FileData.Receipt applyFile(com.agentplatform.domain.files.FileData.Command c){throw new AssertionError("Legacy route not allowed");}
            public com.agentplatform.domain.files.FileData.Receipt queryFile(com.agentplatform.domain.files.FileData.Command c){throw new AssertionError("Legacy route not allowed");}
            public com.agentplatform.domain.files.FileData.Receipt captureFile(com.agentplatform.domain.files.FileCaptureData.Command c){byte[] body=json.writeValueAsBytes(c);signed.operate(control.sign(com.agentplatform.app.files.FileRunnerService.binding(c.file(),"POST","/internal/file-captures/v1"),body),body);throw new com.agentplatform.domain.files.FileFailure(503,"FIXTURE_RESPONSE_LOST");}
            public com.agentplatform.domain.files.FileData.Receipt queryCapture(com.agentplatform.domain.files.FileData.Command c){String path="/internal/file-captures/v1/"+c.operation().id();var response=signed.query(control.sign(com.agentplatform.app.files.FileRunnerService.binding(c,"GET",path),new byte[0]),c.operation().id().toString());control.verify(response.signature(),com.agentplatform.app.files.FileRunnerService.binding(c,"RESPONSE",path),response.body());return json.readValue(response.body(),com.agentplatform.domain.files.FileData.Receipt.class);}
        };
        var timers=java.util.concurrent.Executors.newScheduledThreadPool(2);
        try(var execution=new com.agentplatform.app.files.FileExecution(files,transport,assets,timers)){execution.run(command);}finally{timers.shutdownNow();}
        assertEquals("COMMITTED",files.operation(fileActor,command.operation().id()).status());
        var entry=files.list(fileActor,workspace,"",null,20).stream().filter(e->e.relativePath().equals("result.txt")).findFirst().orElseThrow();
        var version=files.version(fileActor,entry.id(),entry.currentVersionId());assertEquals(target.versionId(),version.id());assertEquals(hash,version.sha256());
        assertEquals(65536,files.usage(fileActor,workspace).reservedBytes());assertEquals(3,files.usage(fileActor,workspace).committedBytes());
        assertEquals("1",scalar("SELECT count(*) FROM pf.file_version WHERE workspace_id='"+workspace+"'"));
        org.mockito.Mockito.verify(runtime,org.mockito.Mockito.times(1)).capture(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        assertFalse(uses.stopCommitted(source.command().id()),"Capture completion cannot by itself release the original use/sandbox slot");
    }
    @Test void productionCompositionQueriesLostShellResponseAndSettlesResultExactlyOnce()throws Exception {
        var request=prepared();
        var runnerInstance=new RunnerInstance(UUID.randomUUID(),randomHash(),"sha256:"+"6".repeat(64),"2026-10-28T00:00:00Z");
        transactions.executeWithoutResult(status->mapperSession.getMapper(EnvironmentExecutionMapper.class).registerRunner(runnerInstance));
        var invocations=new java.util.concurrent.atomic.AtomicInteger();
        var wire=new WorkspaceShellRunnerPort(){
            public EnvironmentUseData.Receipt executeShell(EnvironmentUseData.Command command){
                invocations.incrementAndGet();assertTrue(uses.accept(command,runnerInstance.id()));String exec=randomHash();uses.recordExec(command,runnerInstance.id(),command.containerId(),exec);
                var stop=new SandboxRuntime.Stop(command.containerId(),command.imageId(),command.containerStartedAt(),java.time.Instant.now().minusMillis(10).toString(),true,true,java.time.Instant.now());
                uses.commitFact(command,runnerInstance.id(),new EnvironmentUseData.Fact(command.id(),command.environmentId(),command.generation(),command.fence(),command.requestHash(),runnerInstance.id(),"KNOWN",
                    new SandboxRuntime.Output(exec,0,"666","",false,false,false,stop),stop,null));
                throw new WorkspaceFailure(503,"FIXTURE_RESPONSE_LOST");
            }
            public EnvironmentUseData.Receipt queryShell(EnvironmentUseData.Command command){return uses.receipt(command);}
        };
        var lifecycle=org.mockito.Mockito.mock(EnvironmentLifecycleRepository.class);
        org.mockito.Mockito.when(lifecycle.get(workspaceActor(),workspace)).thenReturn(new EnvironmentPreparationRepository.Snapshot(
            UUID.randomUUID(),workspace,"READY",1,1,UUID.randomUUID(),"PREPARE","COMMITTED",null,false,false,true,false,true));
        var content=new java.util.concurrent.ConcurrentHashMap<String,byte[]>();var resultTransfers=new java.util.concurrent.atomic.AtomicInteger();
        var assets=new com.agentplatform.domain.files.AssetStore(){
            public Identity put(String key,long size,String hash,java.io.InputStream stream){if(resultTransfers.incrementAndGet()==1)throw new com.agentplatform.domain.files.FileFailure(423,"FILE_TRANSFER_BUSY");try{var bytes=stream.readAllBytes();assertEquals(size,bytes.length);content.put(key,bytes);return new Identity(key,size,hash);}catch(java.io.IOException failure){throw new IllegalStateException(failure);}}
            public Content open(String key,Long first,Long last){var bytes=Objects.requireNonNull(content.get(key));return new Content(new java.io.ByteArrayInputStream(bytes),bytes.length,bytes.length);}
            public Identity inspect(String key){var bytes=Objects.requireNonNull(content.get(key));return new Identity(key,bytes.length,com.agentplatform.infrastructure.workspace.RunnerSignatures.sha256(bytes));}
            public void deleteUncommitted(String key){throw new AssertionError("No result deletion");}
        };
        var jobs=new com.agentplatform.infrastructure.adapter.repository.chat.MyBatisWorkspaceToolJobs(mapperSession.getMapper(com.agentplatform.infrastructure.dao.chat.WorkspaceToolJobMapper.class));
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor();var timers=java.util.concurrent.Executors.newScheduledThreadPool(2)) {
            var service=new WorkspaceToolsApplicationService(repository,captureFiles(),assets,lifecycle,uses,wire,jobs,transactions,executor,timers,"sha256:"+"a".repeat(64),"b".repeat(64));
            var exchange=service.open(current,tool);var result=exchange.result().toCompletableFuture().get(10,java.util.concurrent.TimeUnit.SECONDS);
            exchange.terminated().toCompletableFuture().get(1,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(OperationStatus.SUCCEEDED,result.status());assertTrue(result.text().contains("666"));assertEquals(1,invocations.get());assertEquals(2,resultTransfers.get());
            repository.commitTool(current,tool,result);
            assertEquals("3",scalar("SELECT committed_bytes FROM pf.space_storage WHERE space_id='"+actor.spaceId()+"'"));
            assertEquals("0",scalar("SELECT reserved_bytes FROM pf.space_storage WHERE space_id='"+actor.spaceId()+"'"));
            assertEquals("1",scalar("SELECT count(*) FROM pf.tool_result_object WHERE operation_id='"+request.parent().operationId()+"'"));
            assertEquals("0",scalar("SELECT count(*) FROM pf.workspace_sandbox_slot WHERE workspace_id='"+workspace+"'"));
            assertEquals("666",new WorkspaceOperationsService(repository,assets).result(new com.agentplatform.api.chat.ChatApi.Caller(actor.userId().toString(),actor.spaceId().toString()),request.parent().operationId().toString()).stdout());
        }
    }

    @Test void workerStopReceiptCannotReleaseATaskWhileItsRunnerChildHasNoTerminationProof()throws Exception {
        var request=prepared();uses.admitShell(workspaceActor(),workspace,request.parent().operationId(),current.ownerId(),request);
        assertEquals("WORKSPACE_TOOL_STOP_UNCONFIRMED",assertThrows(ChatFailure.class,()->repository.stopped(current,"FAILED","FIXTURE_INTERRUPTED",null,randomHash())).code());
        assertEquals("1",scalar("SELECT count(*) FROM pf.execution_slot WHERE task_id='"+current.taskId()+"'"));
        assertEquals("0",scalar("SELECT count(*) FROM pf.execution_stop_receipt WHERE task_id='"+current.taskId()+"'"));
    }
    @Test void cancelledUnknownShellCanReleaseOnlyAfterTheOriginalWholeContainerStopAndKeepsAccountingHolds()throws Exception {
        var request=prepared();var use=uses.admitShell(workspaceActor(),workspace,request.parent().operationId(),current.ownerId(),request).command();
        var runner=new RunnerInstance(UUID.randomUUID(),randomHash(),"sha256:"+"6".repeat(64),"2026-10-28T00:00:00Z");
        transactions.executeWithoutResult(status->mapperSession.getMapper(EnvironmentExecutionMapper.class).registerRunner(runner));
        assertTrue(uses.accept(use,runner.id()));uses.recordExec(use,runner.id(),use.containerId(),randomHash());
        repository.cancel(actor,current.taskId(),UUID.randomUUID());repository.requestWorkspaceToolStop(current,tool,WorkspaceToolLedger.StopReason.CANCEL);
        var stop=new SandboxRuntime.Stop(use.containerId(),use.imageId(),use.containerStartedAt(),java.time.Instant.now().minusMillis(10).toString(),true,true,java.time.Instant.now());
        uses.commitFact(use,runner.id(),new EnvironmentUseData.Fact(use.id(),use.environmentId(),use.generation(),use.fence(),use.requestHash(),runner.id(),"UNKNOWN",null,stop,"EXECUTION_INTERRUPTED"));
        uses.complete(use);repository.stopped(current,"CANCELLED","TASK_CANCEL_REQUESTED",null,randomHash());
        assertEquals("BLOCKED",repository.task(actor,current.taskId()).status());
        assertEquals("0",scalar("SELECT count(*) FROM pf.execution_slot WHERE task_id='"+current.taskId()+"'"));
        assertEquals("65536",scalar("SELECT reserved_bytes FROM pf.space_storage WHERE space_id='"+actor.spaceId()+"'"));
        assertEquals("UNKNOWN",scalar("SELECT status FROM pf.tool_operation WHERE task_id='"+current.taskId()+"'"));
    }

}
