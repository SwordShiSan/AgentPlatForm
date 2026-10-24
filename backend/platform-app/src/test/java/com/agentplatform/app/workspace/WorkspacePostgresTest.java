package com.agentplatform.app.workspace;

import com.agentplatform.domain.workspace.*;
import com.agentplatform.domain.workspace.WorkspaceData.*;
import com.agentplatform.infrastructure.adapter.repository.workspace.MyBatisWorkspaceRepository;
import com.agentplatform.infrastructure.dao.workspace.WorkspaceMapper;
import com.agentplatform.infrastructure.dao.typehandler.UuidTypeHandler;
import com.agentplatform.infrastructure.workspace.RunnerSignatures;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.mybatis.spring.*;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Destructive tests only in the explicitly isolated R31 workspace_test database. */
class WorkspacePostgresTest {
    private static String url,password;
    private static boolean r32;
    private com.zaxxer.hikari.HikariDataSource pool;
    private WorkspaceRepository repository;
    private Actor alice,bob;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    @BeforeAll static void migrate() throws Exception {
        r32="1".equals(System.getenv("R32_ACCEPTANCE"));
        url=System.getenv(r32?"R32_TEST_JDBC_URL":"R31_TEST_JDBC_URL");Assumptions.assumeTrue(url!=null,"Workspace PostgreSQL not selected; NOT_RUN");
        assertEquals("1",System.getenv(r32?"R32_ACCEPTANCE":"R31_ACCEPTANCE"));
        assertTrue(url.startsWith(r32?"jdbc:postgresql://127.0.0.1:15449/environment_test?":"jdbc:postgresql://127.0.0.1:15448/workspace_test?"));
        password=Files.readString(Path.of(System.getenv(r32?"R32_TEST_PASSWORD_FILE":"R31_TEST_PASSWORD_FILE"))).strip();
        var flyway=Flyway.configure().dataSource(url,"postgres",password).defaultSchema("pf").schemas("pf","pf_runtime").cleanDisabled(false).load();
        flyway.clean();
        Flyway.configure().dataSource(url,"postgres",password).defaultSchema("pf").schemas("pf","pf_runtime").target("9").load().migrate();
        assertEquals(9,count("SELECT max(version::int) FROM pf.flyway_schema_history WHERE success"));
        flyway.migrate();assertEquals(13,count("SELECT max(version::int) FROM pf.flyway_schema_history WHERE success"));
        assertEquals(1,count("SELECT schema_version FROM pf.baseline_version WHERE id=1"));
    }
    private static void sql(String text) throws Exception {
        try(var c=DriverManager.getConnection(url,"postgres",password);var s=c.createStatement()){s.execute(text);}
    }
    private static long count(String text) throws Exception {
        try(var c=DriverManager.getConnection(url,"postgres",password);var s=c.createStatement();var rows=s.executeQuery(text)){rows.next();return rows.getLong(1);}
    }
    @BeforeEach void setup() throws Exception {
        sql("TRUNCATE pf.app_user CASCADE");
        sql("UPDATE pf.workspace_capacity SET reserved_bytes=0,reserved_slots=0,next_project_id=200001");
        var cfg=new com.zaxxer.hikari.HikariConfig();cfg.setJdbcUrl(url);cfg.setUsername("postgres");cfg.setPassword(password);cfg.setMaximumPoolSize(4);cfg.setMinimumIdle(1);
        pool=new com.zaxxer.hikari.HikariDataSource(cfg);
        repository=repositoryFor(pool);
        var identity=com.agentplatform.app.identity.IdentityPersistenceFixture.create(pool);
        UUID a=identity.provision(UUID.randomUUID(),"alice","alice","not-a-login-password",true);
        UUID b=identity.provision(UUID.randomUUID(),"bob","bob","not-a-login-password",false);
        alice=new Actor(a,identity.spaces(a).getFirst().id());bob=new Actor(b,identity.spaces(b).getFirst().id());
    }
    private WorkspaceRepository repositoryFor(com.zaxxer.hikari.HikariDataSource selected) throws Exception {
        var factory=new SqlSessionFactoryBean();factory.setDataSource(selected);
        var config=new org.apache.ibatis.session.Configuration();config.setMapUnderscoreToCamelCase(true);config.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);config.setCacheEnabled(false);config.getTypeHandlerRegistry().register(new UuidTypeHandler());
        factory.setConfiguration(config);factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mybatis/mapper/workspace/*.xml"));
        var mapper=new SqlSessionTemplate(factory.getObject()).getMapper(WorkspaceMapper.class);
        return new TransactionalWorkspaceRepository(new MyBatisWorkspaceRepository(mapper),new TransactionTemplate(new JdbcTransactionManager(selected)));
    }
    @AfterEach void close(){if(pool!=null)pool.close();}
    private static String hash(String value){return RunnerSignatures.sha256(value.getBytes(StandardCharsets.UTF_8));}
    private Workspace create(){return repository.create(alice,UUID.randomUUID(),hash("same"),"研究项目 😀");}
    private Command claim(){var item=repository.publications(UUID.randomUUID()).getFirst();return repository.claim(item,UUID.randomUUID());}
    @Test void atomicIdempotencyAndPhysicalCapacity() throws Exception {
        UUID key=UUID.randomUUID();var first=repository.create(alice,key,hash("same"),"研究项目");
        assertEquals(first.id(),repository.create(alice,key,hash("same"),"研究项目").id());
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(WorkspaceFailure.class,()->repository.create(alice,key,hash("other"),"不同")).code());
        assertEquals("WORKSPACE_CAPACITY_UNAVAILABLE",assertThrows(WorkspaceFailure.class,()->repository.create(bob,UUID.randomUUID(),hash("bob"),"bob")).code());
        assertEquals(1,count("SELECT count(*) FROM pf.workspace"));assertEquals(1,count("SELECT count(*) FROM pf.workspace_outbox"));assertEquals(2147483648L,count("SELECT reserved_bytes FROM pf.workspace_capacity"));
    }
    @Test void concurrentIdenticalRequestsHaveOneWorkspace() throws Exception {
        UUID key=UUID.randomUUID();try(var threads=Executors.newVirtualThreadPerTaskExecutor()){
            var a=threads.submit(()->repository.create(alice,key,hash("same"),"并发"));var b=threads.submit(()->repository.create(alice,key,hash("same"),"并发"));
            assertEquals(a.get(10,TimeUnit.SECONDS).id(),b.get(10,TimeUnit.SECONDS).id());
        }
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_operation"));assertEquals(1,count("SELECT count(*) FROM pf.workspace_reservation"));
    }
    @Test void transactionFailureRollsBackEveryAllocationFact() throws Exception {
        sql("ALTER TABLE pf.workspace_outbox ADD CONSTRAINT fixture_reject_outbox CHECK(false) NOT VALID");
        try{assertThrows(WorkspaceFailure.class,this::create);}finally{sql("ALTER TABLE pf.workspace_outbox DROP CONSTRAINT fixture_reject_outbox");}
        assertEquals(0,count("SELECT count(*) FROM pf.workspace"));assertEquals(0,count("SELECT reserved_slots FROM pf.workspace_capacity"));assertEquals(0,count("SELECT count(*) FROM pf.workspace_idempotency"));
    }
    @Test void realAuthorizationAndAdminDoNotBypassSpace() throws Exception {
        var workspace=create();
        assertThrows(WorkspaceFailure.class,()->repository.get(bob,workspace.id()));
        assertThrows(WorkspaceFailure.class,()->repository.get(new Actor(alice.userId(),bob.spaceId()),workspace.id()));
        sql("UPDATE pf.membership SET status='REVOKED' WHERE user_id='"+alice.userId()+"'");
        assertThrows(WorkspaceFailure.class,()->repository.get(alice,workspace.id()));
    }
    @Test void membershipRevocationIsOrderedWithAuthorizationTransactionCommit() throws Exception {
        var workspace=create();var transactions=new TransactionTemplate(new JdbcTransactionManager(pool));
        transactions.execute(status->{
            assertEquals(workspace.id(),repository.get(alice,workspace.id()).id());
            try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
                var blocked=executor.submit(()->{
                    try(var connection=DriverManager.getConnection(url,"postgres",password);var statement=connection.createStatement()) {
                        statement.execute("SET lock_timeout='500ms'");
                        var denied=assertThrows(java.sql.SQLException.class,()->statement.execute("UPDATE pf.membership SET status='REVOKED' WHERE user_id='"+alice.userId()+"'"));
                        assertEquals("55P03",denied.getSQLState());return true;
                    }
                });assertTrue(blocked.get(3,TimeUnit.SECONDS));
            }catch(Exception failed){throw new IllegalStateException(failed);}
            return null;
        });
        sql("UPDATE pf.membership SET status='REVOKED' WHERE user_id='"+alice.userId()+"'");
        assertThrows(WorkspaceFailure.class,()->repository.get(alice,workspace.id()));
    }
    @Test void duplicateHandoffOldFenceAndExpiredLeaseReject() throws Exception {
        create();var publication=repository.publications(UUID.randomUUID()).getFirst();Command command=repository.claim(publication,UUID.randomUUID());
        assertNull(repository.claim(publication,UUID.randomUUID()));assertEquals(1,count("SELECT count(*) FROM pf.workspace_inbox"));
        var stale=new Command(command.commandId(),command.operationId(),command.spaceId(),command.workspaceId(),command.actorId(),command.ownerId(),command.generation(),command.fence()+1,command.kind(),command.quotaBytes(),command.quotaInodes(),command.projectId(),command.deadline());
        assertThrows(WorkspaceFailure.class,()->repository.renew(stale));assertThrows(WorkspaceFailure.class,()->repository.beforeCall(stale));
        sql("UPDATE pf.workspace_operation SET lease_until=clock_timestamp()-interval '1 second'");
        assertThrows(WorkspaceFailure.class,()->repository.beforeCall(command));
        assertNull(repository.claim(publication,UUID.randomUUID()),"Expired lease is not proof of stopped execution");
    }
    @Test void committedReceiptKeepsExactBytesAndStorageOnlyReadiness() throws Exception {
        var workspace=create();Command command=claim();repository.beforeCall(command);assertNull(repository.accept(command,hash("command")));
        assertEquals("RUNNING",repository.accept(command,hash("command")).state());
        assertThrows(WorkspaceFailure.class,()->repository.accept(command,hash("changed")));
        var receipt=new Receipt(command.commandId(),command.operationId(),command.workspaceId(),command.generation(),command.fence(),"COMMITTED","ALLOCATED",null,hash("synthetic-allocation-not-XFS-proof"),1);
        String json=JSON.writeValueAsString(receipt);repository.commitReceipt(command,receipt,json,hash(json));assertEquals(receipt,repository.receipt(command));repository.complete(command,receipt);
        assertEquals("READY",repository.get(alice,workspace.id()).state());assertEquals("NOT_CREATED",repository.get(alice,workspace.id()).environmentState());
        sql("UPDATE pf.workspace_operation SET lease_until=clock_timestamp()-interval '1 second'");
        assertEquals(receipt,repository.accept(command,hash("command")),"A terminal duplicate reads the original receipt, without a new effect");
        assertEquals(2,count("SELECT count(*) FROM pf.workspace_event"));
    }
    @Test void unknownNeverRefundsOrAllowsRetry() throws Exception {
        var workspace=create();Command command=claim();repository.beforeCall(command);repository.uncertain(command,"RUNNER_RESULT_UNKNOWN");
        var blocked=repository.get(alice,workspace.id());assertEquals("BLOCKED",blocked.state());
        assertThrows(WorkspaceFailure.class,()->repository.retry(alice,workspace.id(),UUID.randomUUID(),hash("retry"),blocked.version()));
        assertEquals(2147483648L,count("SELECT reserved_bytes FROM pf.workspace_capacity"));
    }
    @Test void expiredOwnerBecomesBlockedWithoutReleasingOrReassigningAnything() throws Exception {
        var workspace=create();var publication=repository.publications(UUID.randomUUID()).getFirst();
        Command command=repository.claim(publication,UUID.randomUUID());repository.beforeCall(command);
        sql("UPDATE pf.workspace_operation SET lease_until=clock_timestamp()-interval '1 second'");
        assertEquals(1,repository.expireUnresolved());assertEquals(0,repository.expireUnresolved());
        assertEquals("BLOCKED",repository.get(alice,workspace.id()).state());
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_operation WHERE owner_id='"+command.ownerId()+"' AND fence=1 AND status='UNKNOWN'"));
        assertEquals(2147483648L,count("SELECT reserved_bytes FROM pf.workspace_capacity"));
        assertThrows(WorkspaceFailure.class,()->repository.renew(command));assertThrows(WorkspaceFailure.class,()->repository.beforeCall(command));
        assertNull(repository.claim(publication,UUID.randomUUID()));
        assertEquals(2,count("SELECT count(*) FROM pf.workspace_event"));
    }
    @Test void forgedDeliveryIsQuarantinedBeforeAcknowledgement() throws Exception {
        var workspace=create();var item=repository.publications(UUID.randomUUID()).getFirst();
        var forged=new Publication(UUID.randomUUID(),item.operationId(),item.spaceId(),item.workspaceId(),item.generation(),item.fence(),item.relayOwner());
        assertNull(repository.claim(forged,UUID.randomUUID()));
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_quarantine"));
        assertEquals(0,count("SELECT count(*) FROM pf.workspace_inbox"));
        assertEquals("PROVISIONING",repository.get(alice,workspace.id()).state());
    }
    @Test void runnerRoleCanCommitReceiptButCannotReadSecretsOrChat() throws Exception {
        for(String role:List.of("ap_api","ap_worker","ap_runner"))sql("DO $$ BEGIN CREATE ROLE "+role+" NOLOGIN; EXCEPTION WHEN duplicate_object THEN NULL; END $$");
        sql(Files.readString(Path.of(System.getenv(r32?"R32_WORKSPACE_GRANTS_FILE":"R31_GRANTS_FILE"))));
        create();Command command=claim();repository.beforeCall(command);
        var config=new com.zaxxer.hikari.HikariConfig();config.setJdbcUrl(url);config.setUsername("postgres");config.setPassword(password);
        config.setMaximumPoolSize(2);config.setMinimumIdle(1);config.setConnectionInitSql("SET ROLE ap_runner");
        try(var restricted=new com.zaxxer.hikari.HikariDataSource(config)) {
            var runner=repositoryFor(restricted);assertEquals(command,runner.lookup(command.operationId()));
            assertNull(runner.accept(command,hash("role-bound-command")));
            var receipt=new Receipt(command.commandId(),command.operationId(),command.workspaceId(),command.generation(),command.fence(),"COMMITTED","ALLOCATED",null,hash("role-bound-synthetic-proof"),1);
            String json=JSON.writeValueAsString(receipt);runner.commitReceipt(command,receipt,json,hash(json));assertEquals(receipt,runner.receipt(command));
            for(String query:List.of("SELECT * FROM pf.credential","SELECT * FROM pf.message","SELECT * FROM pf_runtime.state_slot","SELECT * FROM pf.app_user","SELECT id FROM pf.app_user","UPDATE pf.workspace_operation SET fence=fence+1")) {
                try(var c=restricted.getConnection();var statement=c.createStatement()) {
                    var denied=assertThrows(java.sql.SQLException.class,()->statement.execute(query));assertEquals("42501",denied.getSQLState());
                }
            }
        }
    }
    @Test void ownerExpiryAtCommitRollsBackTheWholeWrite() throws Exception {
        create();Command command=claim();
        var transactions=new TransactionTemplate(new JdbcTransactionManager(pool));
        assertThrows(WorkspaceFailure.class,()->transactions.execute(status->{
            repository.beforeCall(command);
            var connection=org.springframework.jdbc.datasource.DataSourceUtils.getConnection(pool);
            try(var statement=connection.createStatement()) {
                statement.execute("UPDATE pf.workspace_operation SET lease_until=clock_timestamp()-interval '1 second'");
            }catch(java.sql.SQLException failure){throw new IllegalStateException(failure);}
            return null;
        }));
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_operation WHERE status='CLAIMED' AND effect_state='NOT_STARTED' AND lease_until>clock_timestamp()"));
        assertEquals(0,count("SELECT count(*) FROM pf.runner_command"));
    }
    @Test void freshInstallKeepsMigrationAndRuntimeVersionsSeparate() throws Exception {
        String database="workspace_fresh_"+UUID.randomUUID().toString().replace("-","");
        assertTrue(database.matches("workspace_fresh_[a-f0-9]{32}"));
        administrativeSql("CREATE DATABASE "+database);
        String fresh=url.replace(r32?"/environment_test?":"/workspace_test?","/"+database+"?");
        try {
            var flyway=Flyway.configure().dataSource(fresh,"postgres",password).defaultSchema("pf").schemas("pf","pf_runtime").load();
            flyway.migrate();
            try(var connection=DriverManager.getConnection(fresh,"postgres",password);var statement=connection.createStatement()) {
                try(var result=statement.executeQuery("SELECT schema_version FROM pf.baseline_version WHERE id=1")){assertTrue(result.next());assertEquals(1,result.getInt(1));}
                try(var result=statement.executeQuery("SELECT max(version::int) FROM pf.flyway_schema_history WHERE success")){assertTrue(result.next());assertEquals(13,result.getInt(1));}
            }
        }finally{administrativeSql("DROP DATABASE "+database);}
    }
    /** Database CREATE/DROP may force an fsync checkpoint; business connections keep their existing timeout. */
    private static void administrativeSql(String text) throws Exception {
        String administrativeUrl=url.replace("socketTimeout=5","socketTimeout=30");
        try(var connection=DriverManager.getConnection(administrativeUrl,"postgres",password);var statement=connection.createStatement()) {
            statement.setQueryTimeout(30);statement.execute(text);
        }
    }
    private org.springframework.amqp.rabbit.connection.CachingConnectionFactory broker() throws Exception {
        String file=System.getenv(r32?"R32_TEST_MQ_PASSWORD_FILE":"R31_TEST_MQ_PASSWORD_FILE");
        Assumptions.assumeTrue(file!=null,"Workspace MQ not selected; NOT_RUN");
        var connection=new org.springframework.amqp.rabbit.connection.CachingConnectionFactory("127.0.0.1",r32?15699:15698);
        connection.setPublisherConfirmType(org.springframework.amqp.rabbit.connection.CachingConnectionFactory.ConfirmType.CORRELATED);connection.setPublisherReturns(true);
        connection.setUsername(r32?"r32":"r31");connection.setPassword(Files.readString(Path.of(file)).strip());
        connection.setVirtualHost("/agentplatform");connection.setConnectionTimeout(2000);
        new org.springframework.amqp.rabbit.core.RabbitAdmin(connection).declareBinding(new org.springframework.amqp.core.Binding("ap.workspace.provision.v1",org.springframework.amqp.core.Binding.DestinationType.QUEUE,"ap.workspace.commands.v1","workspace.provision",Map.of()));
        return connection;
    }
    @Test void realBrokerConfirmLossAndTwoConsumersKeepOneDurableOwner() throws Exception {
        create();var item=repository.publications(UUID.randomUUID()).getFirst();
        var a=broker();var b=broker();
        new org.springframework.amqp.rabbit.core.RabbitAdmin(a).purgeQueue("ap.workspace.provision.v1");
        var claims=new java.util.concurrent.atomic.AtomicInteger();var effects=new java.util.concurrent.atomic.AtomicInteger();
        var delivered=new CountDownLatch(3);var executed=new CountDownLatch(1);
        com.agentplatform.infrastructure.workspace.SpringWorkspaceMessaging.Handoff handoff=new com.agentplatform.infrastructure.workspace.SpringWorkspaceMessaging.Handoff(){
            @Override public Command claim(Publication publication){var result=repository.claim(publication,UUID.randomUUID());claims.incrementAndGet();delivered.countDown();return result;}
            @Override public void execute(Command command){effects.incrementAndGet();executed.countDown();}
        };
        try(var first=new com.agentplatform.infrastructure.workspace.SpringWorkspaceMessaging(a,repository);
                var second=new com.agentplatform.infrastructure.workspace.SpringWorkspaceMessaging(b,repository)) {
            first.start(handoff);second.start(handoff);
            first.publish(item);
            repository.published(item,false); // Simulate publisher losing the confirmation after broker acceptance.
            // SKIP LOCKED permits an empty relay batch while a concurrent claim is committing.
            // Poll the real outbox without clearing its lease or changing the ownership assertions.
            var relay=UUID.randomUUID();Publication repeated=null;long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(repeated==null&&System.nanoTime()<until){var batch=repository.publications(relay);if(!batch.isEmpty())repeated=batch.getFirst();else Thread.sleep(20);}
            assertNotNull(repeated,"Confirmed-loss replay must become eligible in the real outbox");
            first.publish(repeated);first.publish(repeated);
            assertTrue(delivered.await(15,TimeUnit.SECONDS));assertTrue(executed.await(5,TimeUnit.SECONDS));
            assertEquals(3,claims.get());assertEquals(1,effects.get());
            assertEquals(1,count("SELECT count(*) FROM pf.workspace_inbox"));
            assertEquals(1,count("SELECT count(*) FROM pf.workspace_operation WHERE owner_id IS NOT NULL"));
        }finally{a.destroy();b.destroy();}
    }
    @Test void realAckConnectionLossDoesNotExecuteASecondOwner() throws Exception {
        create();var item=repository.publications(UUID.randomUUID()).getFirst();var connection=broker();
        new org.springframework.amqp.rabbit.core.RabbitAdmin(connection).purgeQueue("ap.workspace.provision.v1");
        var attempts=new java.util.concurrent.atomic.AtomicInteger();var effects=new java.util.concurrent.atomic.AtomicInteger();var redelivery=new CountDownLatch(1);
        try(var messaging=new com.agentplatform.infrastructure.workspace.SpringWorkspaceMessaging(connection,repository)) {
            messaging.start(new com.agentplatform.infrastructure.workspace.SpringWorkspaceMessaging.Handoff(){
                @Override public Command claim(Publication publication){
                    Command result=repository.claim(publication,UUID.randomUUID());
                    if(attempts.incrementAndGet()==1){assertNotNull(result);throw new IllegalStateException("Injected channel loss after durable handoff and before ACK");}
                    assertNull(result);redelivery.countDown();return null;
                }
                @Override public void execute(Command command){effects.incrementAndGet();}
            });
            messaging.publish(item);assertTrue(redelivery.await(20,TimeUnit.SECONDS));
            assertEquals(0,effects.get());assertEquals(1,count("SELECT count(*) FROM pf.workspace_inbox"));
            assertEquals(1,count("SELECT count(*) FROM pf.workspace_operation WHERE status='CLAIMED' AND owner_id IS NOT NULL"));
        }finally{connection.destroy();}
    }
    @Test void mandatoryReturnedPublicationRemainsInDurableOutbox() throws Exception {
        create();var item=repository.publications(UUID.randomUUID()).getFirst();var connection=broker();
        var admin=new org.springframework.amqp.rabbit.core.RabbitAdmin(connection);
        var binding=new org.springframework.amqp.core.Binding("ap.workspace.provision.v1",org.springframework.amqp.core.Binding.DestinationType.QUEUE,
                "ap.workspace.commands.v1","workspace.provision",Map.of());
        admin.removeBinding(binding);
        try(var messaging=new com.agentplatform.infrastructure.workspace.SpringWorkspaceMessaging(connection,repository)) {
            assertThrows(WorkspaceFailure.class,()->messaging.publish(item));repository.published(item,false);
            assertEquals(1,count("SELECT count(*) FROM pf.workspace_outbox WHERE status='PENDING'"));
            assertEquals(0,count("SELECT count(*) FROM pf.workspace_inbox"));
        }finally{admin.declareBinding(binding);connection.destroy();}
    }
    @Test void rollbackBeforeClaimCommitIsRedeliveredBeforeAnyExecution() throws Exception {
        create();var item=repository.publications(UUID.randomUUID()).getFirst();var connection=broker();
        new org.springframework.amqp.rabbit.core.RabbitAdmin(connection).purgeQueue("ap.workspace.provision.v1");
        var deliveries=new java.util.concurrent.atomic.AtomicInteger();var executions=new java.util.concurrent.atomic.AtomicInteger();var completed=new CountDownLatch(1);
        var transactions=new TransactionTemplate(new JdbcTransactionManager(pool));
        try(var messaging=new com.agentplatform.infrastructure.workspace.SpringWorkspaceMessaging(connection,repository)) {
            messaging.start(new com.agentplatform.infrastructure.workspace.SpringWorkspaceMessaging.Handoff(){
                @Override public Command claim(Publication publication){
                    if(deliveries.incrementAndGet()==1)return transactions.execute(status->{
                        assertNotNull(repository.claim(publication,UUID.randomUUID()));throw new IllegalStateException("Injected process boundary before claim commit");
                    });
                    return repository.claim(publication,UUID.randomUUID());
                }
                @Override public void execute(Command command){executions.incrementAndGet();completed.countDown();}
            });
            messaging.publish(item);assertTrue(completed.await(20,TimeUnit.SECONDS));assertEquals(1,executions.get());
            assertEquals(1,count("SELECT count(*) FROM pf.workspace_inbox"));
            assertEquals(1,count("SELECT count(*) FROM pf.workspace_operation WHERE status='CLAIMED' AND owner_id IS NOT NULL"));
        }finally{connection.destroy();}
    }
}
