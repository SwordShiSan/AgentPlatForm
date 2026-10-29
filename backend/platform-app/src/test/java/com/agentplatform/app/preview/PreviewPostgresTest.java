package com.agentplatform.app.preview;

import com.agentplatform.app.files.TransactionalFileRepository;
import com.agentplatform.app.identity.IdentityPersistenceFixture;
import com.agentplatform.app.workspace.TransactionalWorkspaceRepository;
import com.agentplatform.domain.files.*;
import com.agentplatform.domain.files.FileData.Actor;
import com.agentplatform.domain.files.FileData.UploadRequest;
import com.agentplatform.domain.preview.*;
import com.agentplatform.domain.preview.PreviewData.*;
import com.agentplatform.infrastructure.adapter.repository.files.*;
import com.agentplatform.infrastructure.adapter.repository.workspace.MyBatisWorkspaceRepository;
import com.agentplatform.infrastructure.dao.files.*;
import com.agentplatform.infrastructure.dao.workspace.*;
import com.agentplatform.infrastructure.dao.typehandler.UuidTypeHandler;
import com.zaxxer.hikari.*;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL/Mapper/role tests. Synthetic source/stop rows are not physical-renderer evidence. */
class PreviewPostgresTest {
    private static String url,password;
    private static final String IMAGE="sha256:"+"a".repeat(64),POLICY="b".repeat(64),HASH="c".repeat(64);
    private HikariDataSource pool;private PreviewRepository previews;private FileRepository files;private Actor alice,bob;private UUID workspace;
    private record VersionFixture(UUID fileId,UUID versionId,UUID assetId,String objectKey) {}
    private static Flyway flyway(String target){var config=Flyway.configure().dataSource(url,"postgres",password).defaultSchema("pf").schemas("pf","pf_runtime").cleanDisabled(false);if(target!=null)config.target(target);return config.load();}
    @BeforeAll static void migrate()throws Exception {
        url=System.getenv("R53_TEST_JDBC_URL");Assumptions.assumeTrue(url!=null,"R53 isolated PostgreSQL NOT_RUN");
        assertEquals("1",System.getenv("R53_ACCEPTANCE"));assertTrue(url.startsWith("jdbc:postgresql://127.0.0.1:15453/preview_test?"));
        password=Files.readString(Path.of(System.getenv("R53_TEST_PASSWORD_FILE"))).strip();
        flyway(null).clean();flyway("19").migrate();assertEquals(19,count("SELECT max(version::int) FROM pf.flyway_schema_history WHERE success"));
        flyway(null).migrate();assertEquals(20,count("SELECT max(version::int) FROM pf.flyway_schema_history WHERE success"));
        assertEquals(1,count("SELECT schema_version FROM pf.baseline_version WHERE id=1"));
        for(String role:List.of("ap_migration","ap_api","ap_worker","ap_runner","ap_workspace_ops"))sql("DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='"+role+"') THEN CREATE ROLE "+role+" NOLOGIN;END IF;END $$");
        for(String name:List.of("r1-runtime-grants.sql","r2-runtime-grants.sql","r3-workspace-grants.sql","r3-environment-grants.sql","r3-file-grants.sql","r4-runtime-grants.sql","r4-permission-grants.sql","r4-approval-grants.sql","r4-workspace-tools-grants.sql","r5-file-grants.sql","r5-preview-grants.sql"))sql(Files.readString(Path.of("../../deploy/config",name)));
    }
    private static void sql(String statement)throws Exception{try(var connection=DriverManager.getConnection(url,"postgres",password);var command=connection.createStatement()){command.execute(statement);}}
    private static long count(String statement)throws Exception{try(var connection=DriverManager.getConnection(url,"postgres",password);var command=connection.createStatement();var rows=command.executeQuery(statement)){rows.next();return rows.getLong(1);}}
    private static SqlSessionTemplate session(HikariDataSource pool)throws Exception {
        var factory=new SqlSessionFactoryBean();factory.setDataSource(pool);var configuration=new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);configuration.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);configuration.setCacheEnabled(false);
        configuration.setDefaultStatementTimeout(5);configuration.getTypeHandlerRegistry().register(new UuidTypeHandler());factory.setConfiguration(configuration);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mybatis/mapper/**/*.xml"));return new SqlSessionTemplate(factory.getObject());
    }
    private HikariDataSource pool(String role){var config=new HikariConfig();config.setJdbcUrl(url);config.setUsername("postgres");config.setPassword(password);config.setMaximumPoolSize(4);config.setMinimumIdle(1);if(role!=null)config.setConnectionInitSql("SET ROLE "+role);return new HikariDataSource(config);}
    private PreviewRepository repository(HikariDataSource selected)throws Exception{var session=session(selected);return new TransactionalPreviewRepository(new MyBatisPreviewRepository(session.getMapper(PreviewMapper.class),session.getMapper(FileMapper.class),IMAGE,"preview-r53-v1",POLICY),new TransactionTemplate(new JdbcTransactionManager(selected)));}
    @BeforeEach void setup()throws Exception {
        sql("TRUNCATE pf.app_user CASCADE");sql("UPDATE pf.workspace_capacity SET reserved_bytes=0,reserved_slots=0,next_project_id=200001");
        sql("UPDATE pf.heavy_runtime_slot SET kind=NULL,reference_id=NULL,generation=NULL,owner_id=NULL");
        sql("INSERT INTO pf.workspace_sandbox_slot(id) VALUES(1) ON CONFLICT(id) DO UPDATE SET space_id=NULL,workspace_id=NULL,environment_id=NULL,generation=NULL");
        sql("UPDATE pf.file_transfer_slot SET owner_id=NULL,object_key_hash=NULL,process_role=NULL,process_id=NULL,started_at=NULL");
        pool=pool(null);var session=session(pool);var tx=new TransactionTemplate(new JdbcTransactionManager(pool));previews=repository(pool);
        var identity=IdentityPersistenceFixture.create(pool);UUID user=identity.provision(UUID.randomUUID(),"r53-alice","r53_alice","fixture-not-login",true);
        UUID other=identity.provision(UUID.randomUUID(),"r53-bob","r53_bob","fixture-not-login",false);
        alice=new Actor(user,identity.spaces(user).getFirst().id());bob=new Actor(other,identity.spaces(other).getFirst().id());
        var ws=new TransactionalWorkspaceRepository(new MyBatisWorkspaceRepository(session.getMapper(WorkspaceMapper.class)),tx);
        workspace=ws.create(new com.agentplatform.domain.workspace.WorkspaceData.Actor(alice.userId(),alice.spaceId()),UUID.randomUUID(),HASH,"R53 ledger-only workspace").id();
        sql("UPDATE pf.workspace SET state='READY' WHERE id='"+workspace+"'");sql("UPDATE pf.workspace_operation SET status='COMMITTED',effect_state='ALLOCATED' WHERE workspace_id='"+workspace+"'");
        files=new TransactionalFileRepository(new MyBatisFileRepository(session.getMapper(FileMapper.class),session.getMapper(WorkspaceMapper.class),session.getMapper(EnvironmentPreparationMapper.class),IMAGE,POLICY),tx);
    }
    @AfterEach void close(){if(pool!=null)pool.close();}
    private VersionFixture source(String name)throws Exception {
        var upload=files.create(alice,workspace,UUID.randomUUID(),HASH,new UploadRequest(name,8,HASH,null,null,null));UUID owner=UUID.randomUUID();
        files.beginTransfer(alice,upload.id(),owner);files.transferVerified(alice,upload.id(),owner);var operation=files.completeUpload(alice,upload.id());
        sql("INSERT INTO pf.file_entry(id,space_id,workspace_id,relative_path,parent_path,name,kind) VALUES('"+upload.fileId()+"','"+alice.spaceId()+"','"+workspace+"','"+name+"','','"+name+"','FILE');"
            +"UPDATE pf.asset_object SET status='AVAILABLE' WHERE id='"+upload.assetId()+"';"
            +"INSERT INTO pf.file_version(id,space_id,workspace_id,file_id,asset_id,operation_id,sha256,size_bytes,actor_id) VALUES('"+upload.versionId()+"','"+alice.spaceId()+"','"+workspace+"','"+upload.fileId()+"','"+upload.assetId()+"','"+operation.id()+"','"+HASH+"',8,'"+alice.userId()+"');"
            +"UPDATE pf.file_entry SET current_version_id='"+upload.versionId()+"',collection_state='CONFIRMED' WHERE id='"+upload.fileId()+"';"
            +"UPDATE pf.object_reservation SET status='COMMITTED' WHERE upload_id='"+upload.id()+"';"
            +"UPDATE pf.space_storage SET reserved_bytes=reserved_bytes-8,committed_bytes=committed_bytes+8 WHERE space_id='"+alice.spaceId()+"'");
        return new VersionFixture(upload.fileId(),upload.versionId(),upload.assetId(),upload.objectKey());
    }
    private Job create(VersionFixture source){return previews.create(alice,source.fileId(),source.versionId(),"AUTO",UUID.randomUUID(),HASH);}
    private Command claim(Job job){var publication=previews.publications(UUID.randomUUID()).stream().filter(p->p.previewId().equals(job.id())).findFirst().orElseThrow();previews.published(publication,true);return previews.claim(publication,UUID.randomUUID());}
    private Receipt receipt(Command c,UUID runner,boolean source,String state,boolean retryable){return new Receipt(c.commandId(),c.previewId(),c.generation(),c.fence(),runner,state,"READY".equals(state)?"PDF":null,"READY".equals(state)?"application/pdf":null,"READY".equals(state)?8:0,"READY".equals(state)?HASH:null,"READY".equals(state)?1:null,"READY".equals(state)?source?c.sourceObjectKey():c.outputObjectKey():null,source,"READY".equals(state)?null:"PREVIEW_RENDERER_UNAVAILABLE",retryable,new Stop(null,null,IMAGE,null,true,true,true,Instant.now()));}
    @Test void cacheAndIdempotencyReserveExactlyOnceAndCrossSpaceCannotRead()throws Exception {
        var source=source("sample.docx");UUID request=UUID.randomUUID();var job=previews.create(alice,source.fileId(),source.versionId(),"AUTO",request,HASH);
        assertEquals(job.id(),previews.create(alice,source.fileId(),source.versionId(),"AUTO",request,HASH).id());assertEquals(job.id(),create(source).id());
        assertEquals(104857600,count("SELECT reserved_bytes FROM pf.space_storage WHERE space_id='"+alice.spaceId()+"'"));
        assertEquals(1,count("SELECT count(*) FROM pf.preview_job"));assertEquals(1,count("SELECT count(*) FROM pf.preview_outbox"));
        assertEquals(409,assertThrows(FileFailure.class,()->previews.create(alice,source.fileId(),source.versionId(),"AUTO",request,"d".repeat(64))).status());
        assertEquals(404,assertThrows(FileFailure.class,()->previews.get(bob,job.id())).status());
        assertEquals(job.id(),previews.create(alice,source.fileId(),source.versionId(),"PDF",UUID.randomUUID(),"d".repeat(64)).id());
        sql("UPDATE pf.file_entry SET name='renamed.unsupported',relative_path='renamed.unsupported' WHERE id='"+source.fileId()+"'");
        assertEquals(job.id(),create(source).id());assertEquals(1,count("SELECT count(*) FROM pf.preview_job"));
    }
    @Test void duplicateDeliveryHasOneOwnerAndPreviewBlocksSandboxReservation()throws Exception {
        var job=create(source("sample.docx"));var delivered=previews.publications(UUID.randomUUID()).getFirst();previews.published(delivered,true);var command=previews.claim(delivered,UUID.randomUUID());assertNotNull(command);
        assertNull(previews.claim(delivered,UUID.randomUUID()));
        var publication=new Publication(UUID.randomUUID(),job.id(),job.spaceId(),1,UUID.randomUUID());assertThrows(FileFailure.class,()->previews.claim(publication,UUID.randomUUID()));
        var other=create(source("second.docx"));assertNull(claim(other));assertEquals("QUEUED",previews.get(alice,other.id()).state());
        assertEquals(1,count("SELECT count(*) FROM pf.preview_attempt"));
        var session=session(pool);var tx=new TransactionTemplate(new JdbcTransactionManager(pool));
        assertThrows(RuntimeException.class,()->tx.execute(s->{var mapper=session.getMapper(EnvironmentPreparationMapper.class);mapper.lockSlot();
            try(var statement=session.getConnection().createStatement()){statement.execute("UPDATE pf.workspace_sandbox_slot SET space_id='"+alice.spaceId()+"',workspace_id='"+workspace+"',environment_id='"+UUID.randomUUID()+"',generation=1 WHERE id=1");}
            catch(java.sql.SQLException rejected){throw new IllegalStateException(rejected);}return null;}));
    }
    @Test void sourcePdfUsesOriginalAssetNoDuplicateChargeAndMovedDeletedSourceKeepsVersion()throws Exception {
        var source=source("sample.pdf");var command=claim(create(source));UUID runner=UUID.randomUUID();assertTrue(previews.runnerClaim(command,runner));assertFalse(previews.runnerClaim(command,runner));
        var receipt=receipt(command,runner,true,"READY",false);previews.runnerReceipt(command,runner,receipt);previews.complete(command,receipt);previews.complete(command,receipt);
        var job=previews.get(alice,command.previewId());assertEquals("READY",job.state());assertEquals(source.objectKey(),job.outputObjectKey());
        assertEquals(8,count("SELECT committed_bytes FROM pf.space_storage WHERE space_id='"+alice.spaceId()+"'"));assertEquals(0,count("SELECT reserved_bytes FROM pf.space_storage WHERE space_id='"+alice.spaceId()+"'"));
        assertEquals(1,count("SELECT count(*) FROM pf.preview_asset_reference WHERE source_referenced"));
        UUID deletion=UUID.randomUUID();
        sql("INSERT INTO pf.file_operation(id,space_id,workspace_id,actor_id,kind,command_id,status,effect_state,helper_image_id,helper_policy_hash) VALUES('"+deletion+"','"+alice.spaceId()+"','"+workspace+"','"+alice.userId()+"','DELETE','"+UUID.randomUUID()+"','COMMITTED','CONFIRMED','"+IMAGE+"','"+POLICY+"');"
            +"UPDATE pf.file_entry SET relative_path='moved.pdf',name='moved.pdf',deleted_at=clock_timestamp(),deletion_operation_id='"+deletion+"',retain_until=clock_timestamp()+interval '30 days' WHERE id='"+source.fileId()+"'");
        assertEquals("READY",previews.get(alice,job.id()).state());sql("UPDATE pf.membership SET status='REVOKED' WHERE user_id='"+alice.userId()+"'");assertThrows(FileFailure.class,()->previews.get(alice,job.id()));
    }
    @Test void derivedOutputSettlesActualBytesAndUnknownNeverStartsAgain()throws Exception {
        var command=claim(create(source("sample.docx")));UUID runner=UUID.randomUUID();previews.runnerClaim(command,runner);previews.unknown(command,"PREVIEW_RESULT_UNKNOWN");
        assertEquals("BLOCKED",previews.get(alice,command.previewId()).state());assertThrows(FileFailure.class,()->previews.runnerClaim(command,UUID.randomUUID()));
        var receipt=receipt(command,runner,false,"READY",false);previews.runnerReceipt(command,runner,receipt);previews.complete(command,receipt);
        assertEquals(16,count("SELECT committed_bytes FROM pf.space_storage WHERE space_id='"+alice.spaceId()+"'"));
        assertEquals(0,count("SELECT reserved_bytes FROM pf.space_storage WHERE space_id='"+alice.spaceId()+"'"));assertEquals(1,count("SELECT count(*) FROM pf.asset_object WHERE category='preview'"));
        assertEquals(0,count("SELECT count(*) FROM pf.heavy_runtime_slot WHERE kind IS NOT NULL"));
    }
    @Test void oneAutomaticInfrastructureRetryHasNewCommandAndNoDoubleRefund()throws Exception {
        var command=claim(create(source("sample.docx")));UUID runner=UUID.randomUUID();previews.runnerClaim(command,runner);var failed=receipt(command,runner,false,"FAILED",true);
        previews.runnerReceipt(command,runner,failed);previews.complete(command,failed);var queued=previews.get(alice,command.previewId());assertEquals("QUEUED",queued.state());assertEquals(2,queued.generation());
        assertEquals(104857600,count("SELECT reserved_bytes FROM pf.space_storage WHERE space_id='"+alice.spaceId()+"'"));
        var next=claim(queued);assertNotEquals(command.commandId(),next.commandId());assertThrows(FileFailure.class,()->previews.guard(command));
        UUID runner2=UUID.randomUUID();previews.runnerClaim(next,runner2);var failed2=receipt(next,runner2,false,"FAILED",true);previews.runnerReceipt(next,runner2,failed2);previews.complete(next,failed2);
        assertEquals("FAILED",previews.get(alice,next.previewId()).state());assertEquals(0,count("SELECT reserved_bytes FROM pf.space_storage WHERE space_id='"+alice.spaceId()+"'"));
        assertEquals(2,count("SELECT count(*) FROM pf.preview_attempt"));
    }
    @Test void forgedStopAndOldOwnerRejectButRevocationCanStillCommitStopWithoutPublishing()throws Exception {
        var command=claim(create(source("sample.docx")));UUID runner=UUID.randomUUID();previews.runnerClaim(command,runner);
        previews.runnerCreated(command,runner,"e".repeat(64));previews.runnerStartRequested(command,runner);previews.runnerStarted(command,runner,"2026-10-29T00:00:00Z");
        var forged=receipt(command,runner,false,"READY",false);assertThrows(FileFailure.class,()->previews.runnerReceipt(command,runner,forged));
        var stop=new Stop("e".repeat(64),null,IMAGE,Instant.parse("2026-10-29T00:00:00Z"),true,true,true,Instant.now());
        var real=new Receipt(command.commandId(),command.previewId(),command.generation(),command.fence(),runner,"READY","PDF","application/pdf",8,HASH,1,command.outputObjectKey(),false,null,false,stop);
        sql("UPDATE pf.app_user SET status='DISABLED' WHERE id='"+alice.userId()+"'");assertThrows(FileFailure.class,()->previews.runnerGuard(command,runner));
        previews.runnerReceipt(command,runner,real);previews.complete(command,real);
        assertEquals(1,count("SELECT count(*) FROM pf.preview_job WHERE state='FAILED' AND error_code='PREVIEW_AUTHORIZATION_REVOKED'"));assertEquals(0,count("SELECT count(*) FROM pf.preview_asset_reference"));
        assertEquals(0,count("SELECT count(*) FROM pf.heavy_runtime_slot WHERE kind IS NOT NULL"));assertEquals(0,count("SELECT reserved_bytes FROM pf.space_storage WHERE space_id='"+alice.spaceId()+"'"));
    }
    @Test void minimalRolesCanClaimStageAndSettleButRunnerCannotChangeQuota()throws Exception {
        var source=source("sample.docx");
        try(var apiPool=pool("ap_api");var workerPool=pool("ap_worker");var runnerPool=pool("ap_runner")) {
            var api=repository(apiPool);var worker=repository(workerPool);var renderer=repository(runnerPool);
            var job=api.create(alice,source.fileId(),source.versionId(),"AUTO",UUID.randomUUID(),HASH);
            var publication=worker.publications(UUID.randomUUID()).getFirst();worker.published(publication,true);var command=worker.claim(publication,UUID.randomUUID());
            UUID runnerId=UUID.randomUUID();assertTrue(renderer.runnerClaim(command,runnerId));renderer.runnerGuard(command,runnerId);
            var receipt=receipt(command,runnerId,false,"READY",false);renderer.runnerReceipt(command,runnerId,receipt);worker.complete(command,receipt);assertEquals("READY",api.get(alice,job.id()).state());
            try(var connection=runnerPool.getConnection();var statement=connection.createStatement()){assertThrows(java.sql.SQLException.class,()->statement.execute("UPDATE pf.space_storage SET committed_bytes=0"));assertThrows(java.sql.SQLException.class,()->statement.executeQuery("SELECT * FROM pf.credential"));}
        }
    }
    @Test void sourceTransportCloseReleasesOnlyExactPreviewCommandProcessAndKey()throws Exception {
        var command=claim(create(source("sample.pdf")));UUID runner=UUID.randomUUID();previews.runnerClaim(command,runner);UUID owner=UUID.randomUUID();
        String keyHash=com.agentplatform.infrastructure.workspace.RunnerSignatures.sha256(command.sourceObjectKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        sql("UPDATE pf.file_transfer_slot SET owner_id='"+owner+"',object_key_hash='"+keyHash+"',process_role='RUNNER',process_id='"+runner+"',started_at=clock_timestamp() WHERE id=1");
        assertThrows(FileFailure.class,()->previews.recordSourceTransferClosed(command,UUID.randomUUID()));assertEquals(1,count("SELECT count(*) FROM pf.file_transfer_slot WHERE owner_id IS NOT NULL"));
        previews.recordSourceTransferClosed(command,runner);previews.recordSourceTransferClosed(command,runner);
        assertEquals(0,count("SELECT count(*) FROM pf.file_transfer_slot WHERE owner_id IS NOT NULL"));assertEquals(1,count("SELECT count(*) FROM pf.file_transfer_release WHERE preview_command_id='"+command.commandId()+"'"));
    }
    @Test void neverAcceptedObservationRequiresBothDatabaseClocksAndCannotReexecute()throws Exception {
        var command=claim(create(source("sample.pdf")));UUID observer=UUID.randomUUID();
        assertFalse(previews.runnerObserveNeverAccepted(command,observer));
        sql("UPDATE pf.preview_job SET lease_until=clock_timestamp()-interval '1 second' WHERE id='"+command.previewId()+"'");
        assertFalse(previews.runnerObserveNeverAccepted(command,observer));
        sql("UPDATE pf.preview_job SET deadline_at=clock_timestamp()-interval '1 second' WHERE id='"+command.previewId()+"'");
        assertTrue(previews.runnerObserveNeverAccepted(command,observer));assertFalse(previews.runnerObserveNeverAccepted(command,UUID.randomUUID()));
        assertThrows(FileFailure.class,()->previews.runnerClaim(command,observer));
        var receipt=receipt(command,observer,false,"FAILED",false);previews.runnerReceipt(command,observer,receipt);previews.complete(command,receipt);
        assertEquals("FAILED",previews.get(alice,command.previewId()).state());assertEquals(0,count("SELECT count(*) FROM pf.heavy_runtime_slot WHERE kind IS NOT NULL"));
    }
    @Test void observedOriginalPhysicalCanConvergeAfterLeaseLossButCannotChangeIdentity()throws Exception {
        var command=claim(create(source("sample.pdf")));UUID runner=UUID.randomUUID();previews.runnerClaim(command,runner);
        sql("UPDATE pf.preview_job SET lease_until=clock_timestamp()-interval '1 second',deadline_at=clock_timestamp()-interval '1 second' WHERE id='"+command.previewId()+"'");previews.blockExpired();
        assertThrows(FileFailure.class,()->previews.runnerCreated(command,runner,"f".repeat(64)));
        previews.runnerObservedPhysical(command,runner,"f".repeat(64),"2026-10-29T00:00:00Z",true);
        assertThrows(FileFailure.class,()->previews.runnerObservedPhysical(command,runner,"e".repeat(64),"2026-10-29T00:00:00Z",true));
        var stop=new Stop("f".repeat(64),null,IMAGE,Instant.parse("2026-10-29T00:00:00Z"),true,true,true,Instant.now());
        var receipt=new Receipt(command.commandId(),command.previewId(),command.generation(),command.fence(),runner,"FAILED",null,null,0,null,null,null,false,"PREVIEW_TIMEOUT",false,stop);
        previews.runnerReceipt(command,runner,receipt);previews.complete(command,receipt);assertEquals("FAILED",previews.get(alice,command.previewId()).state());
    }
    @Test void fixedReadExecsAreAppendOnlyAndReceiptBindsTheLastExactExec()throws Exception {
        var command=claim(create(source("sample.pdf")));UUID runner=UUID.randomUUID();previews.runnerClaim(command,runner);
        assertThrows(FileFailure.class,()->previews.runnerExecCreated(command,runner,"d".repeat(64)));
        previews.runnerCreated(command,runner,"f".repeat(64));previews.runnerStartRequested(command,runner);previews.runnerStarted(command,runner,"2026-10-29T00:00:00Z");
        previews.runnerExecCreated(command,runner,"d".repeat(64));previews.runnerExecCreated(command,runner,"d".repeat(64));previews.runnerExecCreated(command,runner,"e".repeat(64));
        assertEquals(2,count("SELECT count(*) FROM pf.preview_read_exec"));
        var wrong=new Receipt(command.commandId(),command.previewId(),command.generation(),command.fence(),runner,"FAILED",null,null,0,null,null,null,false,"PREVIEW_TIMEOUT",false,
            new Stop("f".repeat(64),"d".repeat(64),IMAGE,Instant.parse("2026-10-29T00:00:00Z"),true,true,true,Instant.now()));
        assertThrows(FileFailure.class,()->previews.runnerReceipt(command,runner,wrong));
        var exact=new Receipt(command.commandId(),command.previewId(),command.generation(),command.fence(),runner,"FAILED",null,null,0,null,null,null,false,"PREVIEW_TIMEOUT",false,
            new Stop("f".repeat(64),"e".repeat(64),IMAGE,Instant.parse("2026-10-29T00:00:00Z"),true,true,true,Instant.now()));
        previews.runnerReceipt(command,runner,exact);previews.complete(command,exact);assertEquals("FAILED",previews.get(alice,command.previewId()).state());
    }
    @Test void quotaAndTwoOwnerRacesHaveSingleWinner()throws Exception {
        var a=source("first.docx");var b=source("second.docx");
        sql("UPDATE pf.space_storage SET object_limit_bytes=committed_bytes+104857600 WHERE space_id='"+alice.spaceId()+"'");
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var gate=new java.util.concurrent.CountDownLatch(1);
            var first=executor.submit(()->{gate.await();try{return create(a);}catch(FileFailure denied){assertEquals(409,denied.status());return null;}});
            var second=executor.submit(()->{gate.await();try{return create(b);}catch(FileFailure denied){assertEquals(409,denied.status());return null;}});gate.countDown();
            var winners=Arrays.asList(first.get(10,java.util.concurrent.TimeUnit.SECONDS),second.get(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals(1,winners.stream().filter(Objects::nonNull).count());
            assertEquals(104857600,count("SELECT reserved_bytes FROM pf.space_storage WHERE space_id='"+alice.spaceId()+"'"));
            var publication=previews.publications(UUID.randomUUID()).getFirst();previews.published(publication,true);
            var owner1=executor.submit(()->previews.claim(publication,UUID.randomUUID()));var owner2=executor.submit(()->previews.claim(publication,UUID.randomUUID()));
            assertEquals(1,Arrays.asList(owner1.get(10,java.util.concurrent.TimeUnit.SECONDS),owner2.get(10,java.util.concurrent.TimeUnit.SECONDS)).stream().filter(Objects::nonNull).count());
            assertEquals(1,count("SELECT count(*) FROM pf.preview_attempt"));
        } finally{executor.shutdownNow();}
    }
    @Test void environmentWaitsOnItsOriginalOutboxThenClaimsOnlyAfterPreviewStops()throws Exception {
        var first=source("first.pdf");var second=source("second.pdf");var preview=claim(create(first));
        var session=session(pool);var tx=new TransactionTemplate(new JdbcTransactionManager(pool));
        var preparation=new com.agentplatform.app.workspace.TransactionalEnvironmentPreparationRepository(
            new com.agentplatform.infrastructure.adapter.repository.workspace.MyBatisEnvironmentPreparationRepository(
                session.getMapper(EnvironmentPreparationMapper.class),session.getMapper(WorkspaceMapper.class),IMAGE,POLICY),tx);
        var execution=new com.agentplatform.app.workspace.TransactionalEnvironmentExecutionRepository(
            new com.agentplatform.infrastructure.adapter.repository.workspace.MyBatisEnvironmentExecutionRepository(
                session.getMapper(EnvironmentExecutionMapper.class),session.getMapper(EnvironmentPreparationMapper.class),session.getMapper(WorkspaceMapper.class)),tx);
        var actor=new com.agentplatform.domain.workspace.WorkspaceData.Actor(alice.userId(),alice.spaceId());
        var submitted=preparation.prepare(actor,workspace,UUID.randomUUID(),HASH,0,false);
        assertEquals("PREPARING",submitted.environment().state());assertEquals(1,count("SELECT count(*) FROM pf.workspace_environment_operation WHERE waiting_heavy"));
        assertEquals(0,count("SELECT count(*) FROM pf.workspace_sandbox_slot WHERE environment_id IS NOT NULL"));
        var pending=execution.publications(UUID.randomUUID()).getFirst();execution.published(pending,true);assertNull(execution.claim(pending,UUID.randomUUID()));
        execution.published(pending,true); // A late confirm cannot consume the original requeued job.
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_environment_outbox WHERE status='PENDING'"));assertEquals(0,count("SELECT count(*) FROM pf.workspace_environment_inbox"));
        UUID runner=UUID.randomUUID();previews.runnerClaim(preview,runner);var stopped=receipt(preview,runner,false,"FAILED",false);previews.runnerReceipt(preview,runner,stopped);previews.complete(preview,stopped);
        var redelivered=execution.publications(UUID.randomUUID()).getFirst();assertEquals(pending.id(),redelivered.id());execution.published(redelivered,true);
        var command=execution.claim(redelivered,UUID.randomUUID());assertNotNull(command);assertEquals(submitted.operationId(),command.operationId());assertNull(execution.claim(redelivered,UUID.randomUUID()));
        assertEquals(0,count("SELECT count(*) FROM pf.workspace_environment_operation WHERE waiting_heavy"));assertEquals(1,count("SELECT count(*) FROM pf.heavy_runtime_slot WHERE kind='SANDBOX'"));
        var next=create(second);assertNull(claim(next));assertEquals("QUEUED",previews.get(alice,next.id()).state());
    }
}
