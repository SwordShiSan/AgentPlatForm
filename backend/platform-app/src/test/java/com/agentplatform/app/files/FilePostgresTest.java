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
    private com.agentplatform.domain.workspace.EnvironmentPreparationRepository environmentPreparations;
    private com.agentplatform.domain.workspace.EnvironmentLifecycleRepository lifecycles;
    private Actor alice, bob;
    private UUID workspace;
    private static Flyway flyway(String target) {
        var configuration=Flyway.configure().dataSource(url,"postgres",password).defaultSchema("pf")
                .schemas("pf","pf_runtime").cleanDisabled(false);
        if(target!=null)configuration.target(target);
        return configuration.load();
    }
    @BeforeAll static void migrate() throws Exception {
        boolean r34="1".equals(System.getenv("R34_ACCEPTANCE"));
        url=System.getenv(r34?"R34_TEST_JDBC_URL":"R33_TEST_JDBC_URL");
        Assumptions.assumeTrue(url!=null,"R33 actual PostgreSQL not selected; NOT_RUN");
        assertEquals("1",System.getenv(r34?"R34_ACCEPTANCE":"R33_ACCEPTANCE"));
        assertTrue(url.startsWith(r34?"jdbc:postgresql://127.0.0.1:25434/environment_test?":"jdbc:postgresql://127.0.0.1:15450/file_test?"));
        password=Files.readString(Path.of(System.getenv(r34?"R34_TEST_PASSWORD_FILE":"R33_TEST_PASSWORD_FILE"))).strip();
        flyway(null).clean();flyway("12").migrate();assertEquals(12,count("SELECT max(version::int) FROM pf.flyway_schema_history WHERE success"));
        flyway(null).migrate();assertEquals(14,count("SELECT max(version::int) FROM pf.flyway_schema_history WHERE success"));
        assertEquals(1,count("SELECT schema_version FROM pf.baseline_version WHERE id=1"));
    }
    private static void sql(String text) throws Exception {
        try(var c=DriverManager.getConnection(url,"postgres",password);var s=c.createStatement()) { s.execute(text); }
    }
    private static long count(String text) throws Exception {
        try(var c=DriverManager.getConnection(url,"postgres",password);var s=c.createStatement();var r=s.executeQuery(text)) {r.next();return r.getLong(1);}
    }
    private record ScopedFiles(HikariDataSource pool,FileRepository repository) implements AutoCloseable {
        @Override public void close(){pool.close();}
    }
    private ScopedFiles scopedFiles(String role) throws Exception {
        assertTrue(Set.of("ap_api","ap_worker").contains(role));
        var config=new HikariConfig();config.setJdbcUrl(url);config.setUsername("postgres");config.setPassword(password);
        config.setMaximumPoolSize(1);config.setMinimumIdle(1);config.setConnectionInitSql("SET ROLE "+role);
        var limitedPool=new HikariDataSource(config);
        var factory=new SqlSessionFactoryBean();factory.setDataSource(limitedPool);
        var settings=new org.apache.ibatis.session.Configuration();settings.setMapUnderscoreToCamelCase(true);
        settings.setCacheEnabled(false);settings.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
        settings.getTypeHandlerRegistry().register(new UuidTypeHandler());factory.setConfiguration(settings);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mybatis/mapper/**/*.xml"));
        var session=new SqlSessionTemplate(factory.getObject());
        var tx=new TransactionTemplate(new JdbcTransactionManager(limitedPool));
        return new ScopedFiles(limitedPool,new TransactionalFileRepository(new MyBatisFileRepository(session.getMapper(FileMapper.class),
                session.getMapper(WorkspaceMapper.class),session.getMapper(EnvironmentPreparationMapper.class),"sha256:"+"a".repeat(64),"b".repeat(64)),tx));
    }
    @Test void apiScanAdmissionUsesScopedColumnsAndCannotReadPrivateUseBodies() throws Exception {
        for(String role:List.of("ap_migration","ap_api","ap_worker","ap_runner","ap_workspace_ops"))
            sql("DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='"+role+"') THEN CREATE ROLE "+role+" NOLOGIN;END IF;END $$");
        for(String name:List.of("r1-runtime-grants.sql","r2-runtime-grants.sql","r3-workspace-grants.sql","r3-environment-grants.sql","r3-file-grants.sql"))
            sql(Files.readString(Path.of("../../deploy/config",name)));
        try(var api=scopedFiles("ap_api")) {
            api.repository().scheduleScan();
            api.repository().scheduleScan();
            assertEquals(1,count("SELECT count(*) FROM pf.file_operation WHERE kind='SCAN'"));
            assertEquals(1,count("SELECT count(*) FROM pf.file_outbox"));
            try(var connection=api.pool().getConnection();var statement=connection.createStatement()) {
                assertThrows(java.sql.SQLException.class,()->statement.executeQuery("SELECT request_text FROM pf.workspace_environment_use"));
                assertThrows(java.sql.SQLException.class,()->statement.executeQuery("SELECT * FROM pf.workspace_environment_use_result"));
            }
        }
    }
    private Command expireUnknown(Command command,boolean lease,boolean deadline) throws Exception {
        String leaseTime=lease?"clock_timestamp()-interval '1 second'":"clock_timestamp()+interval '60 seconds'";
        String deadlineTime=deadline?"clock_timestamp()-interval '1 second'":"clock_timestamp()+interval '120 seconds'";
        sql("UPDATE pf.file_operation SET lease_until="+leaseTime+",deadline_at="+deadlineTime+" WHERE id='"+command.operation().id()+"';"
                +"UPDATE pf.workspace_file_lease SET lease_until="+leaseTime+" WHERE operation_id='"+command.operation().id()+"';");
        return runner.lookup(command.operation().spaceId(),command.operation().workspaceId(),command.operation().id());
    }
    @Test void neverAcceptedExpiredUploadHasNegativeReceiptAndRefundsOnlyOnce() throws Exception {
        var command=claim(8);files.beforeCall(command);files.uncertain(command,"FILE_RESULT_UNKNOWN");
        command=expireUnknown(command,true,true);var observer=instance();runner.register(observer);
        assertEquals(command,files.reconciliations().getFirst());
        var receipt=runner.observeNeverAccepted(command,observer.id(),"e".repeat(64));
        assertEquals("NOT_APPLIED",receipt.resultState());assertTrue(receipt.resultText().contains("NEVER_ACCEPTED"));
        var stop=JsonMapper.builder().build().readValue(receipt.stopText(),FileRuntime.Stop.class);
        assertTrue(stop.neverStarted());assertNull(stop.containerId());assertNull(stop.startedAt());assertNull(stop.execId());
        assertEquals(0,count("SELECT count(*) FROM pf.file_runner_command WHERE container_id IS NOT NULL OR start_requested OR exec_id IS NOT NULL"));
        files.reconcileProjection(command,receipt);
        assertEquals("FAILED",files.operation(alice,command.operation().id()).status());assertEquals(0,files.usage(alice,workspace).reservedBytes());
        assertEquals(receipt,runner.observeNeverAccepted(command,observer.id(),"e".repeat(64)));
        var frozen=command;
        assertThrows(FileFailure.class,()->files.reconcileProjection(frozen,receipt));
        assertThrows(FileFailure.class,()->runner.accept(frozen,observer,"e".repeat(64)));
        assertThrows(FileFailure.class,()->runner.created(frozen,observer.id(),"a".repeat(64)));
        assertEquals(1,count("SELECT count(*) FROM pf.file_runner_receipt"));assertEquals(0,files.usage(alice,workspace).reservedBytes());
    }
    @Test void neverAcceptedProofRequiresBothExpiredClocksAndCurrentAuthorization() throws Exception {
        var c=claim(8);files.beforeCall(c);files.uncertain(c,"FILE_RESULT_UNKNOWN");var observer=instance();runner.register(observer);
        assertNull(runner.observeNeverAccepted(c,observer.id(),"e".repeat(64)));
        c=expireUnknown(c,true,false);assertNull(runner.observeNeverAccepted(c,observer.id(),"e".repeat(64)));
        c=expireUnknown(c,false,true);assertNull(runner.observeNeverAccepted(c,observer.id(),"e".repeat(64)));
        var expired=expireUnknown(c,true,true);
        sql("UPDATE pf.app_user SET status='DISABLED' WHERE id='"+alice.userId()+"';");
        assertThrows(FileFailure.class,()->runner.observeNeverAccepted(expired,observer.id(),"e".repeat(64)));
        assertEquals(0,count("SELECT count(*) FROM pf.file_runner_command"));assertEquals(8,count("SELECT reserved_bytes FROM pf.space_storage WHERE space_id='"+alice.spaceId()+"'"));
    }
    @Test void existingAcceptanceCannotBeReclassifiedAsNeverAccepted() throws Exception {
        var c=claim(8);files.beforeCall(c);var original=instance();runner.register(original);runner.accept(c,original,"e".repeat(64));
        files.uncertain(c,"FILE_RESULT_UNKNOWN");var expired=expireUnknown(c,true,true);var observer=instance();runner.register(observer);
        assertNull(runner.observeNeverAccepted(expired,observer.id(),"f".repeat(64)));
        assertEquals(0,count("SELECT count(*) FROM pf.file_runner_receipt"));assertEquals(8,files.usage(alice,workspace).reservedBytes());
    }
    @Test void concurrentNeverAcceptedScanQueriesCommitOneNegativeFactWithoutExecution() throws Exception {
        files.scheduleScan();var publication=files.publications(UUID.randomUUID()).getFirst();var c=files.claim(publication,UUID.randomUUID());
        files.beforeCall(c);files.uncertain(c,"FILE_RESULT_UNKNOWN");var expired=expireUnknown(c,true,true);
        var first=instance();var second=new com.agentplatform.domain.workspace.RunnerInstance(
                UUID.randomUUID(),"d".repeat(64),first.imageId(),first.startedAt());runner.register(first);runner.register(second);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var gate=new java.util.concurrent.CountDownLatch(1);
            var one=executor.submit(()->{gate.await();return runner.observeNeverAccepted(expired,first.id(),"e".repeat(64));});
            var two=executor.submit(()->{gate.await();return runner.observeNeverAccepted(expired,second.id(),"e".repeat(64));});gate.countDown();
            var receipt=one.get(10,java.util.concurrent.TimeUnit.SECONDS);assertEquals(receipt,two.get(10,java.util.concurrent.TimeUnit.SECONDS));
            files.reconcileProjection(expired,receipt);
        }
        assertEquals(1,count("SELECT count(*) FROM pf.file_runner_command"));assertEquals(1,count("SELECT count(*) FROM pf.file_runner_receipt"));
        assertEquals(0,count("SELECT count(*) FROM pf.workspace_file_lease WHERE status<>'FREE'"));
        assertEquals(0,count("SELECT count(*) FROM pf.file_version"));assertEquals("FAILED",files.operation(alice,c.operation().id()).status());
    }
    @Test void realApiAndWorkerRolesReserveRefundAndSettleWithoutQuotaAdministration() throws Exception {
        for(String role:List.of("ap_migration","ap_api","ap_worker","ap_runner","ap_workspace_ops"))
            sql("DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='"+role+"') THEN CREATE ROLE "+role+" NOLOGIN;END IF;END $$");
        for(String name:List.of("r1-runtime-grants.sql","r2-runtime-grants.sql","r3-workspace-grants.sql","r3-environment-grants.sql","r3-file-grants.sql"))
            sql(Files.readString(Path.of("../../deploy/config",name)));
        try(var api=scopedFiles("ap_api");var worker=scopedFiles("ap_worker")) {
            for(boolean applied:List.of(false,true)) {
                var body=new UploadRequest(applied?"role-success.bin":"role-no-effect.bin",8,"a".repeat(64),null,null,null);
                var upload=api.repository().create(alice,workspace,UUID.randomUUID(),"c".repeat(64),body);
                assertEquals(8,api.repository().usage(alice,workspace).reservedBytes());
                var transfer=UUID.randomUUID();var active=api.repository().beginTransfer(alice,upload.id(),transfer);
                if(applied)api.repository().transferVerified(alice,upload.id(),transfer);
                else {
                    sql("UPDATE pf.upload_session SET transfer_until=clock_timestamp()-interval '1 second' WHERE id='"+upload.id()+"'");
                    var fenced=worker.repository().fenceUploadReconciliation(active);
                    assertEquals(409,assertThrows(FileFailure.class,()->api.repository().guardTransfer(alice,upload.id(),transfer)).status());
                    // Models a completed independently verified object; no S3 claim in this DB-only test.
                    worker.repository().confirmRecoveredUpload(fenced);
                    assertEquals(8,api.repository().usage(alice,workspace).reservedBytes());
                    assertEquals(0,count("SELECT count(*) FROM pf.file_outbox"));
                }
                var operation=api.repository().completeUpload(alice,upload.id());
                var publication=worker.repository().publications(UUID.randomUUID()).stream()
                        .filter(item->item.operationId().equals(operation.id())).findFirst().orElseThrow();
                var command=worker.repository().claim(publication,UUID.randomUUID());assertNotNull(command);
                worker.repository().beforeCall(command);
                // This models stopped physical receipts, not an Engine/S3 acceptance claim.
                var instance=instance();runner.register(instance);runner.accept(command,instance,"e".repeat(64));
                if(applied) {
                    var json=JsonMapper.builder().build().writeValueAsString(Map.of("protocol","file-stream-v2","operationId",command.operation().id(),
                            "state","APPLIED","bytes",8,"sha256","a".repeat(64)));
                    runner.recordResult(command,instance.id(),physicalResult(command,instance,"APPLIED",json));
                } else runner.recordResult(command,instance.id(),notStarted(command,"NOT_APPLIED"));
                worker.repository().commitProjection(command,runner.receipt(command));
                assertEquals(0,api.repository().usage(alice,workspace).reservedBytes());
                assertEquals(applied?8:0,api.repository().usage(alice,workspace).committedBytes());
                assertEquals(applied?"COMMITTED":"ABORTED",api.repository().upload(alice,upload.id()).status());
                assertEquals(404,assertThrows(FileFailure.class,()->api.repository().upload(bob,upload.id())).status());
            }
            for(var limited:List.of(api,worker))try(var c=limited.pool().getConnection();var s=c.createStatement()) {
                assertThrows(java.sql.SQLException.class,()->s.execute("UPDATE pf.space_storage SET object_limit_bytes=object_limit_bytes+1"));
                assertThrows(java.sql.SQLException.class,()->s.execute("UPDATE pf.space_storage SET status='ACTIVE'"));
            }
        }
    }
    @Test void expiredUploadFencingCannotReleaseAnActiveTransportOrPublishAFile() throws Exception {
        var upload=create(8);var owner=UUID.randomUUID();var active=files.beginTransfer(alice,upload.id(),owner);
        var transport=transfers.acquire(upload.objectKey());
        assertTrue(files.uploadReconciliations(null).isEmpty());
        sql("UPDATE pf.upload_session SET transfer_until=clock_timestamp()-interval '1 second' WHERE id='"+upload.id()+"'");
        var observed=files.uploadReconciliations(null).getFirst();var fenced=files.fenceUploadReconciliation(observed);
        assertEquals("VERIFYING",fenced.status());assertNull(fenced.transferOwner());
        assertEquals(409,assertThrows(FileFailure.class,()->files.guardTransfer(alice,upload.id(),owner)).status());
        assertEquals(423,assertThrows(FileFailure.class,()->files.confirmRecoveredUpload(fenced)).status());
        assertThrows(FileFailure.class,()->transfers.acquire("another-transfer"));
        assertEquals(8,files.usage(alice,workspace).reservedBytes());assertEquals(0,count("SELECT count(*) FROM pf.file_operation"));
        transfers.release(transport); // Models a confirmed close, never a lease-expiry release.
        files.confirmRecoveredUpload(fenced);assertEquals("VERIFIED",files.upload(alice,upload.id()).status());
        assertEquals(8,files.usage(alice,workspace).reservedBytes());assertEquals(0,count("SELECT count(*) FROM pf.file_outbox"));
        assertEquals(409,assertThrows(FileFailure.class,()->files.confirmRecoveredUpload(fenced)).status());
    }
    @Test void liveUploadCannotBeFencedAndChangedRevisionCannotBeRecovered() throws Exception {
        var upload=create(8);var owner=UUID.randomUUID();var active=files.beginTransfer(alice,upload.id(),owner);
        assertTrue(files.uploadReconciliations(null).isEmpty());
        assertEquals(409,assertThrows(FileFailure.class,()->files.fenceUploadReconciliation(active)).status());
        files.guardTransfer(alice,upload.id(),owner);files.transferUncertain(alice,upload.id(),owner);
        assertEquals(409,assertThrows(FileFailure.class,()->files.fenceUploadReconciliation(active)).status());
        var fenced=files.fenceUploadReconciliation(files.upload(alice,upload.id()));
        files.confirmRecoveredUpload(fenced);assertEquals("VERIFIED",files.upload(alice,upload.id()).status());
        assertEquals(0,count("SELECT count(*) FROM pf.file_operation"));assertEquals(8,files.usage(alice,workspace).reservedBytes());
    }
    @BeforeEach void setup() throws Exception {
        sql("TRUNCATE pf.app_user CASCADE");
        sql("UPDATE pf.workspace_capacity SET reserved_bytes=0,reserved_slots=0,next_project_id=200001");
        // TRUNCATE ... CASCADE clears the singleton even when its scoped columns
        // were NULL. Cross-feature fixtures must restore it just like environment tests.
        sql("INSERT INTO pf.workspace_sandbox_slot(id) VALUES(1) ON CONFLICT(id) DO UPDATE SET space_id=NULL,workspace_id=NULL,environment_id=NULL,generation=NULL");
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
        environmentPreparations=new com.agentplatform.app.workspace.TransactionalEnvironmentPreparationRepository(
                new com.agentplatform.infrastructure.adapter.repository.workspace.MyBatisEnvironmentPreparationRepository(
                        session.getMapper(EnvironmentPreparationMapper.class),workspaceMapper,"sha256:"+"a".repeat(64),"b".repeat(64)),transactions);
        lifecycles=new com.agentplatform.app.workspace.TransactionalEnvironmentLifecycleRepository(
                new com.agentplatform.infrastructure.adapter.repository.workspace.MyBatisEnvironmentLifecycleRepository(
                        session.getMapper(EnvironmentLifecycleMapper.class),session.getMapper(EnvironmentPreparationMapper.class),
                        workspaceMapper,"sha256:"+"a".repeat(64),"b".repeat(64)),transactions);
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
    @Test void lifecycleClosesNewFileMutationsButPreservesUploadReservationAndReadUsage() throws Exception {
        var upload=create(8);UUID transfer=UUID.randomUUID();files.beginTransfer(alice,upload.id(),transfer);files.transferVerified(alice,upload.id(),transfer);
        var actor=new com.agentplatform.domain.workspace.WorkspaceData.Actor(alice.userId(),alice.spaceId());
        var prepared=environmentPreparations.prepare(actor,workspace,UUID.randomUUID(),"c".repeat(64),0,false);
        // Synthetic environment facts for transaction gating only; no physical runtime is claimed.
        sql("UPDATE pf.workspace_environment SET state='READY',container_id='"+"d".repeat(64)
                +"',container_started_at='2026-10-25T00:00:00Z',readiness_hash='"+"e".repeat(64)+"' WHERE workspace_id='"+workspace+"'");
        sql("UPDATE pf.workspace_environment_operation SET status='COMMITTED',effect_state='READY' WHERE id='"+prepared.operationId()+"'");
        lifecycles.admit(actor,workspace,UUID.randomUUID(),"f".repeat(64),1,com.agentplatform.domain.workspace.EnvironmentLifecycleRepository.Intent.SLEEP);
        assertEquals(423,assertThrows(FileFailure.class,()->files.completeUpload(alice,upload.id())).status());
        assertEquals(423,assertThrows(FileFailure.class,()->create(4)).status());
        assertEquals(8,files.usage(alice,workspace).reservedBytes());
        assertEquals("VERIFIED",files.upload(alice,upload.id()).status());
        assertEquals(0,count("SELECT count(*) FROM pf.file_operation"));
    }
    private Upload create(long bytes) {
        return files.create(alice,workspace,UUID.randomUUID(),UUID.randomUUID().toString().replace("-","")+"0".repeat(32),
                new UploadRequest("hello.txt",bytes,"a".repeat(64),null,null,null));
    }
    private record FileBroker(org.springframework.amqp.rabbit.connection.CachingConnectionFactory connection) implements AutoCloseable {
        @Override public void close(){connection.destroy();}
    }
    private FileBroker fileBroker() throws Exception {
        boolean r34="1".equals(System.getenv("R34_ACCEPTANCE"));
        String selected=System.getenv(r34?"R34_FILE_MQ_PORT":"R33_TEST_MQ_PORT");
        Assumptions.assumeTrue(selected!=null,"R33 actual RabbitMQ not selected; NOT_RUN");
        assertEquals(r34?"25436":"15702",selected);
        var connection=new org.springframework.amqp.rabbit.connection.CachingConnectionFactory("127.0.0.1",r34?25436:15702);
        connection.setUsername(r34?"r34-file":"r33");connection.setPassword(Files.readString(Path.of(System.getenv(r34?"R34_FILE_MQ_PASSWORD_FILE":"R33_TEST_MQ_PASSWORD_FILE"))).strip());
        connection.setVirtualHost("/agentplatform");connection.setPublisherConfirmType(org.springframework.amqp.rabbit.connection.CachingConnectionFactory.ConfirmType.CORRELATED);
        connection.setPublisherReturns(true);
        var admin=new org.springframework.amqp.rabbit.core.RabbitAdmin(connection);
        var exchange=new org.springframework.amqp.core.DirectExchange(com.agentplatform.infrastructure.workspace.SpringFileMessaging.EXCHANGE,true,false);
        var queue=org.springframework.amqp.core.QueueBuilder.durable(com.agentplatform.infrastructure.workspace.SpringFileMessaging.QUEUE).build();
        admin.declareExchange(exchange);admin.declareQueue(queue);
        admin.declareBinding(org.springframework.amqp.core.BindingBuilder.bind(queue).to(exchange).with(com.agentplatform.infrastructure.workspace.SpringFileMessaging.ROUTE));
        admin.purgeQueue(queue.getName());return new FileBroker(connection);
    }
    private Publication queuedFilePublication() {
        var upload=create(5);UUID transfer=UUID.randomUUID();files.beginTransfer(alice,upload.id(),transfer);files.transferVerified(alice,upload.id(),transfer);
        files.completeUpload(alice,upload.id());return files.publications(UUID.randomUUID()).getFirst();
    }
    @Test void managedRealMqDuplicateDeliveryAndTwoConsumersHaveOneDurableOwner() throws Exception {
        try(var broker=fileBroker();var first=new com.agentplatform.infrastructure.workspace.SpringFileMessaging(broker.connection(),files);
            var second=new com.agentplatform.infrastructure.workspace.SpringFileMessaging(broker.connection(),files)) {
            var publication=queuedFilePublication();var executed=new java.util.concurrent.atomic.AtomicInteger();
            var deliveries=new CountDownLatch(2);var completed=new CountDownLatch(1);
            java.util.function.Supplier<com.agentplatform.infrastructure.workspace.SpringFileMessaging.Handoff> listener=()->{
                UUID owner=UUID.randomUUID();return new com.agentplatform.infrastructure.workspace.SpringFileMessaging.Handoff(){
                    public Command claim(Publication item){try{return files.claim(item,owner);}finally{deliveries.countDown();}}
                    public void execute(Command command){executed.incrementAndGet();completed.countDown();}
                };
            };
            first.start(listener.get());second.start(listener.get());first.publish(publication);second.publish(publication);
            assertTrue(deliveries.await(15,TimeUnit.SECONDS));assertTrue(completed.await(5,TimeUnit.SECONDS));assertEquals(1,executed.get());
            assertEquals(1,count("SELECT count(*) FROM pf.file_inbox"));assertEquals(1,count("SELECT count(*) FROM pf.workspace_file_lease WHERE status='ACTIVE'"));
            assertEquals(0,count("SELECT count(*) FROM pf.file_version"));assertEquals(5,files.usage(alice,workspace).reservedBytes());
        }
    }
    @Test void managedRealMqAckLossAfterCommittedHandoffDoesNotExecuteOrCreateAnotherOwner() throws Exception {
        try(var broker=fileBroker();var messaging=new com.agentplatform.infrastructure.workspace.SpringFileMessaging(broker.connection(),files)) {
            var publication=queuedFilePublication();var deliveries=new java.util.concurrent.atomic.AtomicInteger();
            var redelivered=new CountDownLatch(1);var executed=new java.util.concurrent.atomic.AtomicInteger();
            messaging.start(new com.agentplatform.infrastructure.workspace.SpringFileMessaging.Handoff(){
                public Command claim(Publication item){
                    var command=files.claim(item,UUID.randomUUID());
                    if(deliveries.incrementAndGet()==1){assertNotNull(command);throw new IllegalStateException("fixture committed handoff before ACK");}
                    assertNull(command);redelivered.countDown();return command;
                }
                public void execute(Command command){executed.incrementAndGet();}
            });
            messaging.publish(publication);assertTrue(redelivered.await(20,TimeUnit.SECONDS));
            assertEquals(0,executed.get());assertEquals(1,count("SELECT count(*) FROM pf.file_inbox"));
            assertEquals(1,count("SELECT count(*) FROM pf.workspace_file_lease WHERE status='ACTIVE'"));
            assertEquals(0,count("SELECT count(*) FROM pf.file_version"));assertEquals(5,files.usage(alice,workspace).reservedBytes());
        }
    }
    @Test void realBrokerConfirmDeliveredButFrameworkCallbackLostRetainsOutboxAndDeduplicatesRetry() throws Exception {
        boolean r34="1".equals(System.getenv("R34_ACCEPTANCE"));
        Assumptions.assumeTrue(System.getenv(r34?"R34_FILE_MQ_PORT":"R33_TEST_MQ_PORT")!=null,"Actual isolated RabbitMQ not selected; NOT_RUN");
        if(r34)assertEquals("25436",System.getenv("R34_FILE_MQ_PORT"));
        var dropped=new java.util.concurrent.atomic.AtomicInteger();
        var loss=new org.springframework.amqp.rabbit.connection.CachingConnectionFactory("127.0.0.1",r34?25436:15702);
        loss.setUsername(r34?"r34-file":"r33");loss.setPassword(Files.readString(Path.of(System.getenv(r34?"R34_FILE_MQ_PASSWORD_FILE":"R33_TEST_MQ_PASSWORD_FILE"))).strip());loss.setVirtualHost("/agentplatform");
        loss.setPublisherConfirmType(org.springframework.amqp.rabbit.connection.CachingConnectionFactory.ConfirmType.CORRELATED);loss.setPublisherReturns(true);
        // Official framework seam: the real broker's ACK reaches this callback but not its correlation future.
        loss.setPublisherChannelFactory((channel,executor)->new org.springframework.amqp.rabbit.connection.PublisherCallbackChannelImpl(channel,executor){
            @Override public void handleAck(long tag,boolean multiple){dropped.incrementAndGet();}
        });
        try(var broker=fileBroker();var normal=new com.agentplatform.infrastructure.workspace.SpringFileMessaging(broker.connection(),files);
            var unconfirmed=new com.agentplatform.infrastructure.workspace.SpringFileMessaging(loss,files)) {
            var publication=queuedFilePublication();var executions=new java.util.concurrent.atomic.AtomicInteger();var deliveries=new CountDownLatch(2);
            normal.start(new com.agentplatform.infrastructure.workspace.SpringFileMessaging.Handoff(){
                final UUID owner=UUID.randomUUID();
                public Command claim(Publication item){try{return files.claim(item,owner);}finally{deliveries.countDown();}}
                public void execute(Command command){executions.incrementAndGet();}
            });
            var unknown=assertThrows(FileFailure.class,()->unconfirmed.publish(publication));assertEquals("FILE_DISPATCH_UNCONFIRMED",unknown.getMessage());
            assertTrue(dropped.get()>0,"real broker confirm must actually reach the controlled callback");
            files.published(publication,false);var retried=files.publications(UUID.randomUUID()).getFirst();
            assertEquals(publication.id(),retried.id());assertEquals(publication.operationId(),retried.operationId());
            normal.publish(retried);files.published(retried,true);assertTrue(deliveries.await(15,TimeUnit.SECONDS));
            assertEquals(1,executions.get());assertEquals(1,count("SELECT count(*) FROM pf.file_inbox"));
            assertEquals(1,count("SELECT count(*) FROM pf.workspace_file_lease WHERE status='ACTIVE'"));
            assertEquals(1,count("SELECT count(*) FROM pf.file_outbox WHERE status='PUBLISHED'"));
            assertEquals(0,count("SELECT count(*) FROM pf.file_version"));assertEquals(5,files.usage(alice,workspace).reservedBytes());
        }finally{loss.destroy();}
    }
    @Test void realBrokerMandatoryReturnNeverMarksOutboxPublishedAndOriginalPublicationCanRecover() throws Exception {
        try(var broker=fileBroker();var messaging=new com.agentplatform.infrastructure.workspace.SpringFileMessaging(broker.connection(),files)) {
            var admin=new org.springframework.amqp.rabbit.core.RabbitAdmin(broker.connection());
            var exchange=new org.springframework.amqp.core.DirectExchange(com.agentplatform.infrastructure.workspace.SpringFileMessaging.EXCHANGE,true,false);
            var queue=org.springframework.amqp.core.QueueBuilder.durable(com.agentplatform.infrastructure.workspace.SpringFileMessaging.QUEUE).build();
            var binding=org.springframework.amqp.core.BindingBuilder.bind(queue).to(exchange).with(com.agentplatform.infrastructure.workspace.SpringFileMessaging.ROUTE);
            var publication=queuedFilePublication();admin.removeBinding(binding);
            try {
                var rejected=assertThrows(FileFailure.class,()->messaging.publish(publication));assertEquals("FILE_DISPATCH_UNCONFIRMED",rejected.getMessage());
                files.published(publication,false);assertEquals(1,count("SELECT count(*) FROM pf.file_outbox WHERE status='PENDING'"));
                assertEquals(0,count("SELECT count(*) FROM pf.file_inbox"));assertEquals(5,files.usage(alice,workspace).reservedBytes());
            }finally{admin.declareBinding(binding);}
            var again=files.publications(UUID.randomUUID()).getFirst();assertEquals(publication.id(),again.id());
            messaging.publish(again);files.published(again,true);assertEquals(1,count("SELECT count(*) FROM pf.file_outbox WHERE status='PUBLISHED'"));
            admin.purgeQueue(queue.getName());
        }
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
        var json=JsonMapper.builder().build().writeValueAsString(Map.of("protocol","file-stream-v2","operationId",c.operation().id(),"state","APPLIED","bytes",8,"sha256","a".repeat(64)));
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
            files.commitProjection(c,limited.receipt(c));
            var unaccepted=claim(8);files.beforeCall(unaccepted);files.uncertain(unaccepted,"FILE_RESULT_UNKNOWN");
            var expired=expireUnknown(unaccepted,true,true);
            var observed=limited.observeNeverAccepted(expired,instance.id(),"f".repeat(64));
            assertEquals("NOT_APPLIED",observed.resultState());files.reconcileProjection(expired,observed);
            assertEquals(0,files.usage(alice,workspace).reservedBytes());
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
