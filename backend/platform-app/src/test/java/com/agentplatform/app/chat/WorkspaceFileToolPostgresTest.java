package com.agentplatform.app.chat;

import com.agentplatform.app.files.*;
import com.agentplatform.domain.chat.*;
import com.agentplatform.domain.chat.ChatData.Lease;
import com.agentplatform.domain.files.*;
import com.agentplatform.infrastructure.adapter.repository.files.MyBatisFileRepository;
import com.agentplatform.infrastructure.dao.files.FileMapper;
import com.agentplatform.infrastructure.dao.typehandler.UuidTypeHandler;
import com.agentplatform.infrastructure.dao.workspace.*;
import com.agentplatform.infrastructure.workspace.RunnerSignatures;
import com.agentplatform.types.TaskPermissionData.Mode;
import com.agentplatform.types.harness.HarnessData.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Actual PG/Mapper ownership and file transactions; object transport is a deterministic fixture. */
class WorkspaceFileToolPostgresTest extends WorkspaceToolsMigrationPostgresTest {
    FileRepository files;WorkspaceFileToolService service;
    Lease fixtureLease;
    final Map<String,byte[]> objects=new HashMap<>();
    @BeforeEach void fileServices() throws Exception {
        repository=ChatPersistenceFixture.create(pool,true);
        var factory=new SqlSessionFactoryBean();factory.setDataSource(pool);
        var configuration=new org.apache.ibatis.session.Configuration();configuration.setMapUnderscoreToCamelCase(true);
        configuration.setCacheEnabled(false);configuration.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
        configuration.getTypeHandlerRegistry().register(new UuidTypeHandler());factory.setConfiguration(configuration);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mybatis/mapper/**/*.xml"));
        var session=new SqlSessionTemplate(factory.getObject());var tx=new TransactionTemplate(new JdbcTransactionManager(pool));
        files=new TransactionalFileRepository(new MyBatisFileRepository(session.getMapper(FileMapper.class),session.getMapper(WorkspaceMapper.class),
            session.getMapper(EnvironmentPreparationMapper.class),"sha256:"+"a".repeat(64),"b".repeat(64)),tx);
        var assets=new AssetStore(){
            public Identity put(String key,long bytes,String hash,InputStream input){throw new AssertionError("This transaction/read probe must not upload objects");}
            public Content open(String key,Long first,Long last){var bytes=Objects.requireNonNull(objects.get(key));return new Content(new ByteArrayInputStream(bytes),bytes.length,bytes.length);}
            public Identity inspect(String key){throw new AssertionError("Not used");}
            public void deleteUncommitted(String key){throw new AssertionError("No cleanup in probe");}
        };
        service=new WorkspaceFileToolService(repository,files,assets,tx);
    }
    private Lease prepare(Call tool) {
        var task=repository.firstAuthorized(actor,UUID.randomUUID(),"R44 file service",UUID.fromString("0199e000-0000-7000-8000-000000000002"),"PLATFORM","file tool",false,workspace,Mode.FULL).task();
        var publication=repository.publications(UUID.randomUUID()).stream().filter(p->p.envelope().aggregateId().equals(task.id())).findFirst().orElseThrow();
        var lease=repository.claim(publication.envelope(),UUID.randomUUID());assertNotNull(lease);fixtureLease=lease;
        var turn=repository.beginTurn(lease);var physical=repository.beginCall(lease,false);repository.startCall(lease,physical,false);
        repository.commitRound(lease,turn,new Turn(UUID.randomUUID().toString(),1,new Round("",List.of(tool),"tool_calls",10,2)),new ExecutionLedger.CallResult(physical,true,"r44-file-fixture",false));
        repository.prepareWorkspaceTools(lease,List.of(tool),"sha256:"+"a".repeat(64),"b".repeat(64));return lease;
    }
    @AfterEach void releaseFixtureOwner() throws Exception {
        // These tests never start physical work. Retire only this test's exact
        // synthetic owner so an unrelated test cannot inherit its global slot.
        if(fixtureLease!=null)sql("UPDATE pf.execution_slot SET task_id=NULL,owner_id=NULL,lease_until=NULL,fencing_token=fencing_token+1 WHERE task_id='"+fixtureLease.taskId()+"' AND owner_id='"+fixtureLease.ownerId()+"' AND fencing_token="+fixtureLease.fence());
    }
    private Call create(String path){var args=new LinkedHashMap<String,Object>();args.put("mode","CREATE");args.put("path",path);args.put("text","666😀");args.put("fileId",null);args.put("baseVersionId",null);args.put("revision",null);return new Call("write-"+UUID.randomUUID(),"workspace_write",args,false);}
    private String scoped(String table){return "SELECT count(*) FROM pf."+table+" WHERE space_id='"+actor.spaceId()+"'";}
    @Test void uploadAndOriginalOwnerAreAdmittedAtomicallyAndCancellationBlocksTransfer() throws Exception {
        var tool=create("result.txt");var lease=prepare(tool);service.admit(lease,tool);
        var admission=repository.workspaceFileAdmission(lease,tool);assertNotNull(admission.uploadId());assertNull(admission.failureCode());
        assertEquals("1",scalar(scoped("upload_session")));assertEquals("1",scalar(scoped("workspace_tool_file_admission")));
        assertEquals("t",scalar("SELECT pf.file_tool_parent_allowed('"+admission.uploadId()+"',NULL,true)"));
        repository.cancel(actor,lease.taskId(),UUID.randomUUID());
        assertEquals("f",scalar("SELECT pf.file_tool_parent_allowed('"+admission.uploadId()+"',NULL,false)"));
        assertEquals("TOOL_PARENT_NO_LONGER_AUTHORIZED",assertThrows(FileFailure.class,()->files.beginTransfer(new FileData.Actor(actor.userId(),actor.spaceId()),admission.uploadId(),UUID.randomUUID())).getMessage());
        assertTrue(repository.workspaceFileStopped(lease,tool),"No physical file job was handed off; stale parent may still observe termination");
    }
    @Test void parentDeadlineAndDurableStopRequestAlsoDenyUnstartedFileEffects() throws Exception {
        var tool=create("stop-before-transfer.txt");var lease=prepare(tool);service.admit(lease,tool);
        var admission=repository.workspaceFileAdmission(lease,tool);
        sql("UPDATE pf.task SET execution_deadline_at=clock_timestamp()-interval '1 second' WHERE id='"+lease.taskId()+"'");
        assertEquals("f",scalar("SELECT pf.file_tool_parent_allowed('"+admission.uploadId()+"',NULL,false)"));
        sql("UPDATE pf.task SET execution_deadline_at=clock_timestamp()+interval '60 seconds' WHERE id='"+lease.taskId()+"'");
        assertEquals("t",scalar("SELECT pf.file_tool_parent_allowed('"+admission.uploadId()+"',NULL,false)"));
        repository.requestWorkspaceToolStop(lease,tool,WorkspaceToolLedger.StopReason.TIMEOUT);
        assertEquals("f",scalar("SELECT pf.file_tool_parent_allowed('"+admission.uploadId()+"',NULL,true)"));
        assertThrows(FileFailure.class,()->files.beginTransfer(new FileData.Actor(actor.userId(),actor.spaceId()),admission.uploadId(),UUID.randomUUID()));
        assertTrue(files.usage(new FileData.Actor(actor.userId(),actor.spaceId()),workspace).reservedBytes()>0,"A stop request must not refund an unresolved reservation");
        assertEquals("0",scalar(scoped("file_runner_receipt")));
    }
    @Test void publicOperationProjectionIsOwnerScopedTypedAndDoesNotExposeWriteContent() throws Exception {
        var tool=create("public-metadata.txt");var lease=prepare(tool);service.admit(lease,tool);
        var page=repository.workspaceOperations(actor,lease.taskId(),null,20);
        assertEquals(1,page.items().size());var view=page.items().getFirst();assertNull(page.nextCursor());
        assertEquals("WRITE",view.review().kind());assertEquals("CREATE",view.review().writeMode());
        assertEquals("public-metadata.txt",view.review().path());assertEquals("NAMED_FILE",view.review().impact());
        String projection=JsonMapper.builder().build().writeValueAsString(page);
        assertFalse(projection.contains("666"));assertFalse(projection.contains("call_json"));assertFalse(projection.contains("resourceJson"));
        assertEquals(0,repository.workspaceOperations(actor,lease.taskId(),view.operationId(),20).items().size());
        assertThrows(ChatFailure.class,()->repository.workspaceOperations(actor,lease.taskId(),null,0));
        assertThrows(ChatFailure.class,()->repository.workspaceOperations(new ChatData.Actor(UUID.randomUUID(),actor.spaceId()),lease.taskId(),null,20));
        sql("DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='ap_api') THEN CREATE ROLE ap_api NOLOGIN;END IF;END $$;GRANT USAGE ON SCHEMA pf TO ap_api;GRANT EXECUTE ON FUNCTION pf.workspace_tool_public_operations(uuid,uuid,uuid,uuid,integer) TO ap_api");
        try(var connection=pool.getConnection();var statement=connection.createStatement()) {
            statement.execute("SET ROLE ap_api");
            try {
                try(var rows=statement.executeQuery("SELECT review_json FROM pf.workspace_tool_public_operations('"+actor.userId()+"','"+actor.spaceId()+"','"+lease.taskId()+"',NULL,20)")) {
                    assertTrue(rows.next());assertFalse(rows.getString(1).contains("666"));assertFalse(rows.next());
                }
                assertThrows(java.sql.SQLException.class,()->statement.executeQuery("SELECT review_json FROM pf.workspace_tool_intent"));
            }finally{statement.execute("RESET ROLE");}
        }
    }
    @Test void knownNameConflictKeepsRunningToolButRollsBackOnlyFileAdmissionAndReturnsFailedObservation() throws Exception {
        UUID directory=UUID.randomUUID();sql("INSERT INTO pf.file_entry(id,space_id,workspace_id,relative_path,parent_path,name,kind,collection_state) VALUES('"+directory+"','"+actor.spaceId()+"','"+workspace+"','occupied','','occupied','DIRECTORY','CONFIRMED')");
        var tool=create("occupied");var lease=prepare(tool);service.admit(lease,tool);
        var admission=repository.workspaceFileAdmission(lease,tool);assertNull(admission.uploadId());assertEquals("FILE_NAME_CONFLICT",admission.failureCode());
        assertEquals("0",scalar(scoped("asset_object")));assertEquals("0",scalar(scoped("upload_session")));
        assertEquals("0",scalar("SELECT reserved_bytes FROM pf.space_storage WHERE space_id='"+actor.spaceId()+"'"));
        var result=service.run(lease,tool,()->false);assertEquals(OperationStatus.FAILED,result.status());repository.commitTool(lease,tool,result);
        assertEquals("FAILED",scalar("SELECT status FROM pf.tool_operation WHERE id='"+admission.operationId()+"'"));
        var stored=repository.workspaceOperationResult(actor,admission.operationId());assertEquals("FAILED",stored.status());
        assertTrue(stored.text().contains("FILE_NAME_CONFLICT"));
    }
    @Test void failureToPersistParentBindingRollsBackUploadQuotaAndToolStartTogether() throws Exception {
        var tool=create("atomic.txt");var lease=prepare(tool);
        sql("CREATE FUNCTION pf.r44_reject_file_admission() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture admission failure';END $$;CREATE TRIGGER r44_reject_file_admission BEFORE INSERT ON pf.workspace_tool_file_admission FOR EACH ROW EXECUTE FUNCTION pf.r44_reject_file_admission()");
        try{assertThrows(RuntimeException.class,()->service.admit(lease,tool));}
        finally{sql("DROP TRIGGER r44_reject_file_admission ON pf.workspace_tool_file_admission;DROP FUNCTION pf.r44_reject_file_admission()");}
        assertEquals("0",scalar(scoped("asset_object")));assertEquals("0",scalar(scoped("upload_session")));
        assertEquals("0",scalar("SELECT reserved_bytes FROM pf.space_storage WHERE space_id='"+actor.spaceId()+"'"));
        assertEquals("PREPARED",scalar("SELECT status FROM pf.tool_operation WHERE task_id='"+lease.taskId()+"'"));
    }
    private FileData.Upload textFixture(String value) throws Exception {
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);var fileActor=new FileData.Actor(actor.userId(),actor.spaceId());
        var upload=files.create(fileActor,workspace,UUID.randomUUID(),"c".repeat(64),new FileData.UploadRequest("文档😀.txt",bytes.length,RunnerSignatures.sha256(bytes),null,null,null));
        UUID transfer=UUID.randomUUID();files.beginTransfer(fileActor,upload.id(),transfer);files.transferVerified(fileActor,upload.id(),transfer);var operation=files.completeUpload(fileActor,upload.id());
        // Seed already sealed metadata only. No physical runner evidence is claimed by this fixture.
        sql("BEGIN;INSERT INTO pf.file_entry(id,space_id,workspace_id,relative_path,parent_path,name,kind,collection_state) VALUES('"+upload.fileId()+"','"+actor.spaceId()+"','"+workspace+"','文档😀.txt','','文档😀.txt','FILE','PENDING');"
            +"INSERT INTO pf.file_version(id,space_id,workspace_id,file_id,asset_id,operation_id,sha256,size_bytes,actor_id) VALUES('"+upload.versionId()+"','"+actor.spaceId()+"','"+workspace+"','"+upload.fileId()+"','"+upload.assetId()+"','"+operation.id()+"','"+upload.sha256()+"',"+bytes.length+",'"+actor.userId()+"');"
            +"UPDATE pf.asset_object SET status='AVAILABLE' WHERE id='"+upload.assetId()+"';"
            +"UPDATE pf.file_entry SET current_version_id='"+upload.versionId()+"',collection_state='CONFIRMED' WHERE id='"+upload.fileId()+"';COMMIT;");
        objects.put(upload.objectKey(),bytes);return upload;
    }
    @Test void explicitVersionReadIsUtf8CodepointPagedAndModelResultBounded() throws Exception {
        var upload=textFixture("😀中文".repeat(3500));var tool=new Call("read-one","workspace_read",Map.of("fileId",upload.fileId().toString(),"versionId",upload.versionId().toString(),"offsetCodePoints",2,"maxCodePoints",4096),true);
        var lease=prepare(tool);service.admit(lease,tool);var result=service.run(lease,tool,()->false);
        assertEquals(OperationStatus.SUCCEEDED,result.status());assertTrue(result.text().getBytes(StandardCharsets.UTF_8).length<=8192);
        var json=JsonMapper.builder().build().readTree(result.text());String page=json.path("text").asText();assertTrue(page.startsWith("文😀中"));
        assertEquals(2+page.codePointCount(0,page.length()),json.path("nextOffsetCodePoints").asLong());
    }
    @Test void corruptImmutableObjectIsRejectedBeforeReturningItsText() throws Exception {
        var upload=textFixture("approved content");objects.put(upload.objectKey(),"corrupted bytes!".getBytes(StandardCharsets.UTF_8));
        var tool=new Call("read-corrupt","workspace_read",Map.of("fileId",upload.fileId().toString(),"versionId",upload.versionId().toString(),"offsetCodePoints",0,"maxCodePoints",16),true);
        var lease=prepare(tool);service.admit(lease,tool);assertThrows(IOException.class,()->service.run(lease,tool,()->false));
    }
    @Test void runnerPredicateDoesNotRequirePrivateChatGrantsAndFileTriggerWorksForApiRole() throws Exception {
        var tool=create("roles.txt");var lease=prepare(tool);service.admit(lease,tool);var admission=repository.workspaceFileAdmission(lease,tool);
        for(String role:List.of("ap_migration","ap_api","ap_worker","ap_runner","ap_workspace_ops"))
            sql("DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='"+role+"') THEN CREATE ROLE "+role+" NOLOGIN;END IF;END $$");
        for(String name:List.of("r1-runtime-grants.sql","r2-runtime-grants.sql","r3-workspace-grants.sql","r3-environment-grants.sql","r3-file-grants.sql",
                "r4-runtime-grants.sql","r4-permission-grants.sql","r4-approval-grants.sql","r4-workspace-tools-grants.sql"))
            sql(java.nio.file.Files.readString(java.nio.file.Path.of("../../deploy/config",name)));
        try(var connection=pool.getConnection();var statement=connection.createStatement()) {
            statement.execute("SET ROLE ap_runner");
            try(var result=statement.executeQuery("SELECT pf.file_tool_parent_allowed('"+admission.uploadId()+"',NULL,true)")){assertTrue(result.next());assertTrue(result.getBoolean(1));}
            for(String table:List.of("pf.task","pf.credential","pf.workspace_tool_file_admission","pf_runtime.state_slot"))
                assertThrows(java.sql.SQLException.class,()->statement.executeQuery("SELECT * FROM "+table));
            statement.execute("RESET ROLE");statement.execute("SET ROLE ap_api");
            try(var result=statement.executeQuery("SELECT pf.current_tool_resource_hash('"+actor.userId()+"','"+actor.spaceId()+"','"+lease.taskId()+"','"+admission.operationId()+"')")){assertTrue(result.next());assertNotNull(result.getString(1));}
            assertThrows(java.sql.SQLException.class,()->statement.executeQuery("SELECT review_json FROM pf.workspace_tool_intent"));
            statement.execute("INSERT INTO pf.file_entry(id,space_id,workspace_id,relative_path,parent_path,name,kind,collection_state) VALUES('"+UUID.randomUUID()+"','"+actor.spaceId()+"','"+workspace+"','directory','','directory','DIRECTORY','CONFIRMED')");
            statement.execute("RESET ROLE");
        }
        assertEquals("1",scalar("SELECT content_revision FROM pf.workspace WHERE id='"+workspace+"'"));
    }
    @Test void cancelledParentBlocksExecutionButChildCanPersistUnknownWithoutReleasingReservation() throws Exception {
        var tool=create("cancelled.txt");var lease=prepare(tool);service.admit(lease,tool);var admission=repository.workspaceFileAdmission(lease,tool);
        var fileActor=new FileData.Actor(actor.userId(),actor.spaceId());UUID transfer=UUID.randomUUID();
        files.beginTransfer(fileActor,admission.uploadId(),transfer);files.transferVerified(fileActor,admission.uploadId(),transfer);
        new TransactionTemplate(new JdbcTransactionManager(pool)).executeWithoutResult(status->{
            var operation=files.completeUpload(fileActor,admission.uploadId());repository.bindWorkspaceFileOperation(lease,tool,operation.id());
        });
        var linked=repository.workspaceFileAdmission(lease,tool);var publication=files.publications(UUID.randomUUID()).stream().filter(p->p.operationId().equals(linked.fileOperationId())).findFirst().orElseThrow();
        var command=files.claim(publication,UUID.randomUUID());assertNotNull(command);files.beforeCall(command);
        repository.cancel(actor,lease.taskId(),UUID.randomUUID());
        assertEquals("TOOL_PARENT_NO_LONGER_AUTHORIZED",assertThrows(FileFailure.class,()->files.guard(command)).getMessage());
        files.uncertain(command,"FILE_RESULT_UNKNOWN");
        assertEquals("UNKNOWN",files.operation(fileActor,linked.fileOperationId()).status());
        assertEquals("BLOCKED",scalar("SELECT status FROM pf.workspace_file_lease WHERE operation_id='"+linked.fileOperationId()+"'"));
        assertTrue(files.usage(fileActor,workspace).reservedBytes()>0);assertFalse(repository.workspaceFileStopped(lease,tool));
        assertEquals("1",scalar(scoped("workspace_tool_job_binding")));assertEquals("1",scalar(scoped("file_outbox")));
    }
    @Test void cancelBeforeFileClaimPersistsANegativeHandoffWithoutInventingARunnerReceipt()throws Exception {
        var tool=create("cancel-unclaimed.txt");var lease=prepare(tool);service.admit(lease,tool);
        var admission=repository.workspaceFileAdmission(lease,tool);var owner=new FileData.Actor(actor.userId(),actor.spaceId());
        var bytes="666😀".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        UUID transfer=UUID.randomUUID();files.beginTransfer(owner,admission.uploadId(),transfer);
        files.transferVerified(owner,admission.uploadId(),transfer);
        var child=files.completeUpload(owner,admission.uploadId());repository.bindWorkspaceFileOperation(lease,tool,child.id());
        var publication=files.publications(UUID.randomUUID()).stream().filter(p->p.operationId().equals(child.id())).findFirst().orElseThrow();
        repository.cancel(actor,lease.taskId(),UUID.randomUUID());repository.requestWorkspaceToolStop(lease,tool,WorkspaceToolLedger.StopReason.CANCEL);
        assertNull(files.claim(publication,UUID.randomUUID()));
        assertEquals("FAILED",files.operation(owner,child.id()).status());assertTrue(repository.workspaceFileStopped(lease,tool));
        assertEquals("0",scalar("SELECT count(*) FROM pf.file_runner_receipt WHERE command_id='"+child.commandId()+"'"));
        assertEquals("1",scalar("SELECT count(*) FROM pf.workspace_tool_unclaimed_child WHERE file_operation_id='"+child.id()+"'"));
        assertEquals("0",scalar("SELECT reserved_bytes FROM pf.space_storage WHERE space_id='"+actor.spaceId()+"'"));
    }

}
