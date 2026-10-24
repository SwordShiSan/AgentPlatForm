package com.agentplatform.app.files;

import com.agentplatform.app.identity.IdentityPersistenceFixture;
import com.agentplatform.app.workspace.TransactionalWorkspaceRepository;
import com.agentplatform.domain.files.*;
import com.agentplatform.domain.files.FileData.*;
import com.agentplatform.domain.workspace.WorkspaceRepository;
import com.agentplatform.infrastructure.adapter.repository.files.MyBatisFileRepository;
import com.agentplatform.infrastructure.adapter.repository.files.MyBatisFileRunnerRepository;
import com.agentplatform.infrastructure.dao.files.FileRunnerMapper;
import com.agentplatform.infrastructure.dao.files.FileTransferMapper;
import com.agentplatform.infrastructure.adapter.repository.files.MyBatisFileTransferPermit;
import com.agentplatform.domain.workspace.RunnerInstance;
import com.agentplatform.infrastructure.workspace.RunnerSignatures;
import java.time.Instant;
import tools.jackson.databind.json.JsonMapper;
import com.agentplatform.infrastructure.adapter.repository.workspace.MyBatisWorkspaceRepository;
import com.agentplatform.infrastructure.dao.files.FileMapper;
import com.agentplatform.infrastructure.dao.typehandler.UuidTypeHandler;
import com.agentplatform.infrastructure.dao.workspace.*;
import com.zaxxer.hikari.*;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated actual PostgreSQL and typed Mapper transactions; not a filesystem/runner substitute. */
class FilePostgresTest {
    private static String url, password;
    private HikariDataSource pool;
    private FileRepository files;
    private FileRunnerRepository runner;
    private FileTransferPermit transfers;
    private FileTransferMapper transferMapper;
    private TransactionTemplate transactionBoundary;
    private WorkspaceRepository workspaces;
    private Actor alice, bob;
    private UUID workspace;
    private static Flyway flyway(String target) {
        var configuration=Flyway.configure().dataSource(url,"postgres",password).defaultSchema("pf")
                .schemas("pf","pf_runtime").cleanDisabled(false);
        if(target!=null)configuration.target(target);
        return configuration.load();
    }
    @BeforeAll static void migrate() throws Exception {
        url=System.getenv("R33_TEST_JDBC_URL");
        Assumptions.assumeTrue(url!=null,"R33 actual PostgreSQL not selected; NOT_RUN");
        assertEquals("1",System.getenv("R33_ACCEPTANCE"));
        assertTrue(url.startsWith("jdbc:postgresql://127.0.0.1:15450/file_test?"));
        password=Files.readString(Path.of(System.getenv("R33_TEST_PASSWORD_FILE"))).strip();
        flyway(null).clean();flyway("12").migrate();assertEquals(12,count("SELECT max(version::int) FROM pf.flyway_schema_history WHERE success"));
        flyway(null).migrate();assertEquals(13,count("SELECT max(version::int) FROM pf.flyway_schema_history WHERE success"));
        assertEquals(1,count("SELECT schema_version FROM pf.baseline_version WHERE id=1"));
    }
    private static void sql(String text) throws Exception {
        try(var c=DriverManager.getConnection(url,"postgres",password);var s=c.createStatement()) { s.execute(text); }
    }
    private static long count(String text) throws Exception {
        try(var c=DriverManager.getConnection(url,"postgres",password);var s=c.createStatement();var r=s.executeQuery(text)) {r.next();return r.getLong(1);}
    }
    @BeforeEach void setup() throws Exception {
        sql("TRUNCATE pf.app_user CASCADE");
        sql("UPDATE pf.workspace_capacity SET reserved_bytes=0,reserved_slots=0,next_project_id=200001");
        var config=new HikariConfig();config.setJdbcUrl(url);config.setUsername("postgres");config.setPassword(password);
        config.setMaximumPoolSize(4);config.setMinimumIdle(1);pool=new HikariDataSource(config);
        var factory=new SqlSessionFactoryBean();factory.setDataSource(pool);
        var settings=new org.apache.ibatis.session.Configuration();settings.setMapUnderscoreToCamelCase(true);
        settings.setCacheEnabled(false);settings.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
        settings.setDefaultStatementTimeout(5);settings.getTypeHandlerRegistry().register(new UuidTypeHandler());factory.setConfiguration(settings);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mybatis/mapper/**/*.xml"));
        var session=new SqlSessionTemplate(factory.getObject());var transactions=new TransactionTemplate(new JdbcTransactionManager(pool));
        transferMapper=session.getMapper(FileTransferMapper.class);transactionBoundary=transactions;
        var workspaceMapper=session.getMapper(WorkspaceMapper.class);
        workspaces=new TransactionalWorkspaceRepository(new MyBatisWorkspaceRepository(workspaceMapper),transactions);
        files=new TransactionalFileRepository(new MyBatisFileRepository(session.getMapper(FileMapper.class),workspaceMapper,
                session.getMapper(EnvironmentPreparationMapper.class),"sha256:"+"a".repeat(64),"b".repeat(64)),transactions);
        runner=new TransactionalFileRunnerRepository(new MyBatisFileRunnerRepository(session.getMapper(FileRunnerMapper.class),session.getMapper(FileMapper.class),
                workspaceMapper,session.getMapper(EnvironmentPreparationMapper.class),session.getMapper(EnvironmentExecutionMapper.class),session.getMapper(FileTransferMapper.class)),transactions);
        transfers=new TransactionalFileTransferPermit(new MyBatisFileTransferPermit(session.getMapper(FileTransferMapper.class)),transactions);
        sql("UPDATE pf.file_transfer_slot SET owner_id=NULL,object_key_hash=NULL,process_role=NULL,process_id=NULL,started_at=NULL");
        var identity=IdentityPersistenceFixture.create(pool);
        UUID a=identity.provision(UUID.randomUUID(),"r33_alice","r33_alice","fixture-not-a-login-password",true);
        UUID b=identity.provision(UUID.randomUUID(),"r33_bob","r33_bob","fixture-not-a-login-password",false);
        alice=new Actor(a,identity.spaces(a).getFirst().id());bob=new Actor(b,identity.spaces(b).getFirst().id());
        workspace=workspaces.create(new com.agentplatform.domain.workspace.WorkspaceData.Actor(alice.userId(),alice.spaceId()),
                UUID.randomUUID(),"a".repeat(64),"R33 metadata fixture").id();
        // DB-only prerequisite; no physical allocation is claimed by these tests.
        sql("UPDATE pf.workspace SET state='READY' WHERE id='"+workspace+"'");
        sql("UPDATE pf.workspace_operation SET status='COMMITTED',effect_state='ALLOCATED' WHERE workspace_id='"+workspace+"'");
    }
    @AfterEach void close() { if(pool!=null)pool.close(); }
    private Upload create(long bytes) {
        return files.create(alice,workspace,UUID.randomUUID(),UUID.randomUUID().toString().replace("-","")+"0".repeat(32),
                new UploadRequest("hello.txt",bytes,"a".repeat(64),null,null,null));
    }
    @Test void uploadReservationAndIdempotencyDoNotCreateSandbox() throws Exception {
        UUID request=UUID.randomUUID();var body=new UploadRequest("hello.txt",6,"a".repeat(64),null,null,null);
        var first=files.create(alice,workspace,request,"b".repeat(64),body);
        var again=files.create(alice,workspace,request,"b".repeat(64),body);
        assertEquals(first.id(),again.id());assertEquals(6,files.usage(alice,workspace).reservedBytes());
        assertEquals(1,count("SELECT count(*) FROM pf.upload_session"));
        assertEquals(0,count("SELECT count(*) FROM pf.workspace_environment"));
        assertEquals(409,assertThrows(FileFailure.class,()->files.create(alice,workspace,request,"c".repeat(64),body)).status());
        assertEquals(404,assertThrows(FileFailure.class,()->files.upload(bob,first.id())).status());
    }
    @Test void concurrentReservationsNeverOversell() throws Exception {
        sql("UPDATE pf.space_storage SET object_limit_bytes=8 WHERE space_id='"+alice.spaceId()+"'");
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->{try{create(6);return true;}catch(FileFailure rejected){assertEquals("FILE_VERSION_QUOTA_EXCEEDED",rejected.getMessage());return false;}});
            var second=executor.submit(()->{try{create(6);return true;}catch(FileFailure rejected){assertEquals("FILE_VERSION_QUOTA_EXCEEDED",rejected.getMessage());return false;}});
            assertNotEquals(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));
        }
        assertEquals(6,files.usage(alice,workspace).reservedBytes());assertEquals(1,count("SELECT count(*) FROM pf.upload_session"));
    }
    @Test void verifiedCompletionPublishesOneOutboxAndClaimHasOneOwner() throws Exception {
        var upload=create(7);UUID transfer=UUID.randomUUID();files.beginTransfer(alice,upload.id(),transfer);
        assertEquals(409,assertThrows(FileFailure.class,()->files.completeUpload(alice,upload.id())).status());
        files.transferVerified(alice,upload.id(),transfer);
        var first=files.completeUpload(alice,upload.id());var again=files.completeUpload(alice,upload.id());
        assertEquals(first.id(),again.id());assertEquals(1,count("SELECT count(*) FROM pf.file_outbox"));
        var publication=files.publications(UUID.randomUUID()).getFirst();
        var owner=files.claim(publication,UUID.randomUUID());assertNotNull(owner);assertEquals(1,owner.operation().fence());
        assertNull(files.claim(publication,UUID.randomUUID()));
        files.guard(owner);files.beforeCall(owner);
        assertEquals("RUNNING",files.operation(alice,first.id()).status());
        assertEquals(1,count("SELECT count(*) FROM pf.file_inbox"));
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_file_lease WHERE status='ACTIVE'"));
        assertEquals(0,count("SELECT count(*) FROM pf.file_version"));
    }
    @Test void revocationAndUnknownDoNotRefundReservations() throws Exception {
        var upload=create(5);UUID transfer=UUID.randomUUID();files.beginTransfer(alice,upload.id(),transfer);
        files.transferUncertain(alice,upload.id(),transfer);
        assertEquals("VERIFYING",files.upload(alice,upload.id()).status());
        assertEquals(5,files.usage(alice,workspace).reservedBytes());
        sql("UPDATE pf.app_user SET status='DISABLED' WHERE id='"+alice.userId()+"'");
        assertEquals(403,assertThrows(FileFailure.class,()->files.upload(alice,upload.id())).status());
        assertEquals(5,count("SELECT reserved_bytes FROM pf.space_storage WHERE space_id='"+alice.spaceId()+"'"));
    }
    private Command claim(long bytes) {
        var upload=create(bytes);var transfer=UUID.randomUUID();files.beginTransfer(alice,upload.id(),transfer);files.transferVerified(alice,upload.id(),transfer);
        files.completeUpload(alice,upload.id());return files.claim(files.publications(UUID.randomUUID()).getFirst(),UUID.randomUUID());
    }
    private RunnerInstance instance(){return new RunnerInstance(UUID.randomUUID(),"c".repeat(64),"sha256:"+"d".repeat(64),Instant.now().minusSeconds(30).toString());}
    private FileRuntime.Result notStarted(Command c,String state) {
        var stop=new FileRuntime.Stop(c.operation().commandId(),null,c.operation().helperImageId(),null,null,null,true,true,true,Instant.now());
        return new FileRuntime.Result(state,"{\"state\":\""+state+"\"}",JsonMapper.builder().build().writeValueAsString(stop));
    }
    private FileRuntime.Result physicalResult(Command c,RunnerInstance instance,String state,String json) {
        String started=Instant.now().minusSeconds(10).toString();
        runner.created(c,instance.id(),"1".repeat(64));runner.startRequested(c,instance.id());runner.started(c,instance.id(),started);
        runner.exec(c,instance.id(),"2".repeat(64));
        var stop=new FileRuntime.Stop(c.operation().commandId(),"1".repeat(64),c.operation().helperImageId(),started,"2".repeat(64),
                Instant.now().minusSeconds(1).toString(),false,true,true,Instant.now());
        return new FileRuntime.Result(state,json,JsonMapper.builder().build().writeValueAsString(stop));
    }
    @Test void runnerReceiptsAndDefiniteNoEffectRefundExactlyOnce() throws Exception {
        var c=claim(8);files.beforeCall(c);var instance=instance();runner.register(instance);
        assertEquals(c,runner.lookup(alice.spaceId(),workspace,c.operation().id()));
        assertTrue(runner.accept(c,instance,"e".repeat(64)));assertFalse(runner.accept(c,instance,"e".repeat(64)));
        runner.recordResult(c,instance.id(),notStarted(c,"NOT_APPLIED"));var receipt=runner.receipt(c);
        files.commitProjection(c,receipt);assertEquals("FAILED",files.operation(alice,c.operation().id()).status());
        assertEquals(0,files.usage(alice,workspace).reservedBytes());assertEquals(0,files.usage(alice,workspace).committedBytes());
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_file_lease WHERE status='FREE'"));
        assertThrows(FileFailure.class,()->files.commitProjection(c,receipt));
        assertEquals(0,files.usage(alice,workspace).reservedBytes());assertEquals(1,count("SELECT count(*) FROM pf.file_runner_receipt"));
    }
    @Test void runnerRejectsMissingStopAndOldFenceAndNeverRefundsUnknown() throws Exception {
        var c=claim(8);files.beforeCall(c);var instance=instance();runner.register(instance);runner.accept(c,instance,"e".repeat(64));
        var malformed=new FileRuntime.Stop(c.operation().commandId(),null,c.operation().helperImageId(),null,null,null,true,true,false,Instant.now());
        assertThrows(FileFailure.class,()->runner.recordResult(c,instance.id(),new FileRuntime.Result("NOT_APPLIED","{}",JsonMapper.builder().build().writeValueAsString(malformed))));
        assertEquals(0,count("SELECT count(*) FROM pf.file_runner_receipt"));
        runner.recordResult(c,instance.id(),notStarted(c,"UNKNOWN"));files.commitProjection(c,runner.receipt(c));
        assertEquals("UNKNOWN",files.operation(alice,c.operation().id()).status());assertEquals(8,files.usage(alice,workspace).reservedBytes());
        assertEquals(1,count("SELECT count(*) FROM pf.object_reservation WHERE status='RECONCILING'"));
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_file_lease WHERE status='BLOCKED'"));
        assertThrows(FileFailure.class,()->files.renew(c));assertThrows(FileFailure.class,()->runner.guard(c,instance.id()));
    }
    @Test void expiredLeaseRetainsResourcesAndBlocksAllOldExecutionWrites() throws Exception {
        var c=claim(8);files.beforeCall(c);
        sql("UPDATE pf.file_operation SET lease_until=clock_timestamp()-interval '1 second' WHERE id='"+c.operation().id()+"'");
        assertThrows(FileFailure.class,()->files.beforeCall(c));assertThrows(FileFailure.class,()->files.renew(c));
        files.blockExpired();files.blockExpired();
        assertEquals("UNKNOWN",files.operation(alice,c.operation().id()).status());assertEquals(8,files.usage(alice,workspace).reservedBytes());
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_file_lease WHERE status='BLOCKED'"));
        assertEquals(0,count("SELECT count(*) FROM pf.file_version"));
    }
    @Test void quarantineStoresOnlyDigestNotOriginalMessage() throws Exception {
        files.quarantine(UUID.randomUUID(),"INVALID_FILE_DELIVERY",RunnerSignatures.sha256(new byte[]{1,2,3}));
        assertEquals(1,count("SELECT count(*) FROM pf.file_delivery_quarantine"));
    }
    @Test void transferAdmissionIsGlobalAndExpiryCannotReassignIt() throws Exception {
        var owner=transfers.acquire("spaces/a/file-version/a/v/k");
        assertEquals(423,assertThrows(FileFailure.class,()->transfers.acquire("spaces/b/file-version/b/v/k")).status());
        sql("UPDATE pf.file_transfer_slot SET started_at=clock_timestamp()-interval '1 day'");
        assertThrows(FileFailure.class,()->transfers.release(UUID.randomUUID()));
        assertThrows(FileFailure.class,()->transfers.acquire("other"));
        transfers.release(owner);var next=transfers.acquire("next");assertNotEquals(owner,next);
        transfers.release(owner);assertThrows(FileFailure.class,()->transfers.acquire("cannot-steal-new-owner"));transfers.release(next);
    }
    @Test void stoppedStoredReceiptCanReconcileExpiredProjectionWithoutNewExecution() throws Exception {
        var c=claim(8);files.beforeCall(c);var instance=instance();runner.register(instance);runner.accept(c,instance,"e".repeat(64));
        runner.recordResult(c,instance.id(),notStarted(c,"NOT_APPLIED"));var receipt=runner.receipt(c);
        sql("UPDATE pf.file_operation SET lease_until=clock_timestamp()-interval '1 second' WHERE id='"+c.operation().id()+"'");
        files.blockExpired();assertThrows(FileFailure.class,()->files.commitProjection(c,receipt));
        assertEquals(1,files.reconciliations().size());files.observeProjection(c);files.reconcileProjection(c,receipt);
        assertEquals("FAILED",files.operation(alice,c.operation().id()).status());assertEquals(0,files.usage(alice,workspace).reservedBytes());
        assertEquals(0,files.reconciliations().size());assertThrows(FileFailure.class,()->files.reconcileProjection(c,receipt));
        assertEquals(1,count("SELECT count(*) FROM pf.file_runner_command"));assertEquals(1,count("SELECT count(*) FROM pf.file_runner_receipt"));
    }
    @Test void scansHaveRealOperationsAndPreserveUnsealedFactsWithoutSandboxOrUpload() throws Exception {
        files.scheduleScan();files.scheduleScan();
        assertEquals(1,count("SELECT count(*) FROM pf.file_operation WHERE kind='SCAN'"));
        var c=files.claim(files.publications(UUID.randomUUID()).getFirst(),UUID.randomUUID());assertNull(c.upload());files.beforeCall(c);
        var instance=instance();runner.register(instance);runner.accept(c,instance,"e".repeat(64));
        var page=new ScanPage("file-scan-v1",c.operation().id(),"SCANNED","",c.operation().scanEpoch(),"1:2:3:4",null,null,false,
                List.of(new ScanItem("目录","DIRECTORY",null,null),new ScanItem("空😀.txt","FILE",6L,"a".repeat(64))));
        runner.recordResult(c,instance.id(),physicalResult(c,instance,"SCANNED",JsonMapper.builder().build().writeValueAsString(page)));
        files.commitProjection(c,runner.receipt(c));var entries=files.list(alice,workspace,"",null,100);assertEquals(2,entries.size());
        assertEquals("PENDING",entries.stream().filter(e->e.kind().equals("FILE")).findFirst().orElseThrow().collectionState());
        assertEquals(0,count("SELECT count(*) FROM pf.upload_session"));assertEquals(0,count("SELECT count(*) FROM pf.workspace_environment"));
        assertEquals(0,files.usage(alice,workspace).reservedBytes());assertEquals(0,count("SELECT count(*) FROM pf.file_version"));
    }
    @Test void appliedReceiptCommitsVersionOnceAndQueuedSameNameLosesWithoutAnotherRunner() throws Exception {
        var first=create(8);var second=create(8);
        for(var upload:List.of(first,second)){
            var transfer=UUID.randomUUID();files.beginTransfer(alice,upload.id(),transfer);files.transferVerified(alice,upload.id(),transfer);files.completeUpload(alice,upload.id());
        }
        var items=files.publications(UUID.randomUUID());var c=files.claim(items.getFirst(),UUID.randomUUID());files.beforeCall(c);
        var instance=instance();runner.register(instance);runner.accept(c,instance,"e".repeat(64));
        var json=JsonMapper.builder().build().writeValueAsString(Map.of("protocol","file-stream-v1","operationId",c.operation().id(),"state","APPLIED","bytes",8,"sha256","a".repeat(64)));
        runner.recordResult(c,instance.id(),physicalResult(c,instance,"APPLIED",json));files.commitProjection(c,runner.receipt(c));
        assertEquals(1,count("SELECT count(*) FROM pf.file_version"));assertEquals(1,count("SELECT count(*) FROM pf.asset_reference"));
        assertEquals(8,files.usage(alice,workspace).committedBytes());assertEquals(8,files.usage(alice,workspace).reservedBytes());
        assertNull(files.claim(items.getLast(),UUID.randomUUID()));assertEquals(0,files.usage(alice,workspace).reservedBytes());
        assertEquals(1,count("SELECT count(*) FROM pf.file_operation WHERE status='FAILED'"));
        assertEquals(1,count("SELECT count(*) FROM pf.file_runner_command"));
        var current=files.file(alice,c.upload().fileId());assertEquals("CONFIRMED",current.collectionState());
        assertEquals(c.upload().versionId(),files.version(alice,current.id(),null).id());
    }
    @Test void actualRunnerRoleCanOnlyWritePhysicalFactsAndCannotReadChatOrChangeOwnership() throws Exception {
        sql("DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='ap_runner') THEN CREATE ROLE ap_runner NOLOGIN;END IF;"
                +"IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='ap_api') THEN CREATE ROLE ap_api NOLOGIN;END IF;"
                +"IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='ap_worker') THEN CREATE ROLE ap_worker NOLOGIN;END IF;"
                +"IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='ap_workspace_ops') THEN CREATE ROLE ap_workspace_ops NOLOGIN;END IF;END $$");
        for(String name:List.of("r3-workspace-grants.sql","r3-environment-grants.sql","r3-file-grants.sql"))
            sql(Files.readString(Path.of("../../deploy/config",name)));
        var c=claim(8);files.beforeCall(c);
        var configuration=new HikariConfig();configuration.setJdbcUrl(url);configuration.setUsername("postgres");configuration.setPassword(password);
        configuration.setMaximumPoolSize(1);configuration.setMinimumIdle(1);configuration.setConnectionInitSql("SET ROLE ap_runner");
        try(var limitedPool=new HikariDataSource(configuration)) {
            var factory=new SqlSessionFactoryBean();factory.setDataSource(limitedPool);
            var settings=new org.apache.ibatis.session.Configuration();settings.setMapUnderscoreToCamelCase(true);settings.setCacheEnabled(false);
            settings.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);settings.getTypeHandlerRegistry().register(new UuidTypeHandler());
            factory.setConfiguration(settings);factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mybatis/mapper/**/*.xml"));
            var session=new SqlSessionTemplate(factory.getObject());var tx=new TransactionTemplate(new JdbcTransactionManager(limitedPool));
            var limited=new TransactionalFileRunnerRepository(new MyBatisFileRunnerRepository(session.getMapper(FileRunnerMapper.class),session.getMapper(FileMapper.class),
                    session.getMapper(WorkspaceMapper.class),session.getMapper(EnvironmentPreparationMapper.class),session.getMapper(EnvironmentExecutionMapper.class),session.getMapper(FileTransferMapper.class)),tx);
            var instance=instance();limited.register(instance);assertTrue(limited.accept(c,instance,"e".repeat(64)));limited.guard(c,instance.id());
            limited.recordResult(c,instance.id(),notStarted(c,"NOT_APPLIED"));assertEquals("NOT_APPLIED",limited.receipt(c).resultState());
            for(String denied:List.of("SELECT * FROM pf.message","SELECT * FROM pf.credential","SELECT * FROM pf_runtime.state_slot",
                    "UPDATE pf.file_operation SET owner_id=NULL","UPDATE pf.workspace_file_lease SET status='FREE'","UPDATE pf.space_storage SET reserved_bytes=0")) {
                try(var connection=limitedPool.getConnection();var statement=connection.createStatement()) {
                    assertThrows(java.sql.SQLException.class,()->statement.execute(denied),denied);
                }
            }
        }
    }
    @Test void expiryTombstonesOnlyProvenInactiveStagingAndRefundsAfterAbsenceOnce() throws Exception {
        var upload=create(8);
        sql("UPDATE pf.upload_session SET expires_at=clock_timestamp()-interval '1 second' WHERE id='"+upload.id()+"'");
        var cleanup=files.claimCleanup();assertEquals(upload.id(),cleanup.id());assertEquals("ABORTED",cleanup.status());
        assertEquals(8,files.usage(alice,workspace).reservedBytes());
        // Models the adapter's independently confirmed absence; this test does not claim an S3 delete.
        files.cleanupComplete(cleanup);files.cleanupComplete(cleanup);
        assertEquals("EXPIRED",files.upload(alice,upload.id()).status());assertEquals(0,files.usage(alice,workspace).reservedBytes());
        assertEquals(1,count("SELECT count(*) FROM pf.asset_object WHERE status='PURGED'"));assertNull(files.claimCleanup());
    }
    @Test void expiredActiveOrUnknownTransfersAreNeverGarbageCollected() throws Exception {
        var upload=create(8);var owner=UUID.randomUUID();files.beginTransfer(alice,upload.id(),owner);
        sql("UPDATE pf.upload_session SET expires_at=clock_timestamp()-interval '1 second' WHERE id='"+upload.id()+"'");
        assertNull(files.claimCleanup());files.transferUncertain(alice,upload.id(),owner);assertNull(files.claimCleanup());
        assertEquals(8,files.usage(alice,workspace).reservedBytes());
    }
    @Test void preEffectTransferBusyAllowsSameUploadIdentityToContinue() {
        var upload=create(8);var first=UUID.randomUUID();files.beginTransfer(alice,upload.id(),first);files.transferRejected(alice,upload.id(),first);
        assertEquals("CREATED",files.upload(alice,upload.id()).status());var second=UUID.randomUUID();files.beginTransfer(alice,upload.id(),second);
        files.transferVerified(alice,upload.id(),second);assertEquals("VERIFIED",files.upload(alice,upload.id()).status());
        assertEquals(8,files.usage(alice,workspace).reservedBytes());
    }
    @Test void runnerTransportReleaseRequiresTheSameInstanceAndConfirmedCallbackStop() throws Exception {
        var c=claim(8);files.beforeCall(c);var instance=instance();runner.register(instance);runner.accept(c,instance,"e".repeat(64));
        var scoped=new TransactionalFileTransferPermit(new MyBatisFileTransferPermit(transferMapper,"RUNNER",instance.id()),transactionBoundary);
        scoped.acquire(c.upload().objectKey());assertThrows(FileFailure.class,()->transfers.acquire("busy"));
        var stop=new FileRuntime.Stop(c.operation().commandId(),null,c.operation().helperImageId(),null,null,null,true,true,false,Instant.now());
        assertThrows(FileFailure.class,()->runner.recordResult(c,instance.id(),new FileRuntime.Result("NOT_APPLIED","{}",JsonMapper.builder().build().writeValueAsString(stop))));
        assertThrows(FileFailure.class,()->transfers.acquire("still-busy"));
        runner.recordResult(c,instance.id(),notStarted(c,"NOT_APPLIED"));
        assertEquals(1,count("SELECT count(*) FROM pf.file_transfer_release WHERE proof_kind='RUNNER_RECEIPT' AND runner_command_id='"+c.operation().commandId()+"'"));
        var next=transfers.acquire("next");transfers.release(next);
    }
    @Test void stoppedRunnerCannotReleaseAWorkerTransferWithTheSameObjectKey() throws Exception {
        var c=claim(8);files.beforeCall(c);var instance=instance();runner.register(instance);runner.accept(c,instance,"e".repeat(64));
        var scoped=new TransactionalFileTransferPermit(new MyBatisFileTransferPermit(transferMapper,"WORKER",UUID.randomUUID()),transactionBoundary);
        var owner=scoped.acquire(c.upload().objectKey());runner.recordResult(c,instance.id(),notStarted(c,"NOT_APPLIED"));
        assertThrows(FileFailure.class,()->transfers.acquire("cannot-steal-worker"));
        assertEquals(0,count("SELECT count(*) FROM pf.file_transfer_release WHERE proof_kind='RUNNER_RECEIPT'"));scoped.release(owner);
    }
    @Test void transferGuardRejectsExpiredOrDifferentOwnerWithoutRefunding()throws Exception {
        var upload=create(8);var owner=UUID.randomUUID();files.beginTransfer(alice,upload.id(),owner);files.guardTransfer(alice,upload.id(),owner);
        assertThrows(FileFailure.class,()->files.guardTransfer(alice,upload.id(),UUID.randomUUID()));
        sql("UPDATE pf.upload_session SET transfer_until=clock_timestamp()-interval '1 second' WHERE id='"+upload.id()+"'");
        assertThrows(FileFailure.class,()->files.guardTransfer(alice,upload.id(),owner));assertEquals(8,files.usage(alice,workspace).reservedBytes());
    }
}
