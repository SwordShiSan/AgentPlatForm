package com.agentplatform.app.workspace;

import com.agentplatform.app.identity.IdentityPersistenceFixture;
import com.agentplatform.domain.workspace.*;
import com.agentplatform.domain.workspace.WorkspaceData.Actor;
import com.agentplatform.infrastructure.adapter.repository.workspace.*;
import com.agentplatform.infrastructure.dao.typehandler.UuidTypeHandler;
import com.agentplatform.infrastructure.dao.workspace.*;
import com.agentplatform.infrastructure.workspace.RunnerSignatures;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
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

/** Actual isolated PG/Mapper admission tests, not a runner/MQ or filesystem substitute. */
class EnvironmentPostgresTest {
    private static String url, password;
    private com.zaxxer.hikari.HikariDataSource pool;
    private TransactionTemplate transactions;
    private EnvironmentPreparationRepository environments;
    private WorkspaceRepository workspaces;
    private EnvironmentExecutionRepository executions;
    private Actor alice, bob;
    private static Flyway flyway(String target) {
        var configuration = Flyway.configure().dataSource(url,"postgres",password).defaultSchema("pf")
                .schemas("pf","pf_runtime").cleanDisabled(false);
        if (target != null) configuration.target(target);
        return configuration.load();
    }
    @BeforeAll static void migrate() throws Exception {
        url = System.getenv("R32_TEST_JDBC_URL");
        Assumptions.assumeTrue(url != null,"R32 PostgreSQL not selected; NOT_RUN");
        assertEquals("1",System.getenv("R32_ACCEPTANCE"));
        assertTrue(url.startsWith("jdbc:postgresql://127.0.0.1:15449/environment_test?"));
        password = Files.readString(Path.of(System.getenv("R32_TEST_PASSWORD_FILE"))).strip();
        flyway(null).clean();
        flyway("10").migrate();
        assertEquals(10,count("SELECT max(version::int) FROM pf.flyway_schema_history WHERE success"));
        flyway(null).migrate();
        assertEquals(11,count("SELECT max(version::int) FROM pf.flyway_schema_history WHERE success"));
        assertEquals(1,count("SELECT schema_version FROM pf.baseline_version WHERE id=1"));
    }
    private static void sql(String text) throws Exception {
        try(var connection=DriverManager.getConnection(url,"postgres",password);var statement=connection.createStatement()) {statement.execute(text);}
    }
    private static long count(String text) throws Exception {
        try(var connection=DriverManager.getConnection(url,"postgres",password);var statement=connection.createStatement();var rows=statement.executeQuery(text)) {rows.next();return rows.getLong(1);}
    }
    private static String hash(String text) {return RunnerSignatures.sha256(text.getBytes(StandardCharsets.UTF_8));}
    @BeforeEach void setup() throws Exception {
        sql("TRUNCATE pf.app_user CASCADE");
        sql("UPDATE pf.workspace_capacity SET reserved_bytes=0,reserved_slots=0,next_project_id=200001");
        sql("INSERT INTO pf.workspace_sandbox_slot(id) VALUES(1) ON CONFLICT(id) DO UPDATE SET space_id=NULL,workspace_id=NULL,environment_id=NULL,generation=NULL");
        var configuration=new com.zaxxer.hikari.HikariConfig();configuration.setJdbcUrl(url);configuration.setUsername("postgres");configuration.setPassword(password);
        configuration.setMaximumPoolSize(4);configuration.setMinimumIdle(1);
        pool=new com.zaxxer.hikari.HikariDataSource(configuration);
        transactions=new TransactionTemplate(new JdbcTransactionManager(pool));
        var factory=new SqlSessionFactoryBean();factory.setDataSource(pool);
        var mybatis=new org.apache.ibatis.session.Configuration();mybatis.setMapUnderscoreToCamelCase(true);
        mybatis.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);mybatis.setCacheEnabled(false);
        mybatis.getTypeHandlerRegistry().register(new UuidTypeHandler());factory.setConfiguration(mybatis);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mybatis/mapper/workspace/*.xml"));
        var session=new SqlSessionTemplate(factory.getObject());var mapper=session.getMapper(WorkspaceMapper.class);
        workspaces=new TransactionalWorkspaceRepository(new MyBatisWorkspaceRepository(mapper),transactions);
        environments=new TransactionalEnvironmentPreparationRepository(new MyBatisEnvironmentPreparationRepository(
                session.getMapper(EnvironmentPreparationMapper.class),mapper,"sha256:"+"a".repeat(64),"b".repeat(64)),transactions);
        executions=new TransactionalEnvironmentExecutionRepository(new MyBatisEnvironmentExecutionRepository(
                session.getMapper(EnvironmentExecutionMapper.class),session.getMapper(EnvironmentPreparationMapper.class),mapper),transactions);
        var identity=IdentityPersistenceFixture.create(pool);
        UUID a=identity.provision(UUID.randomUUID(),"alice","alice","not-a-login-password",true);
        UUID b=identity.provision(UUID.randomUUID(),"bob","bob","not-a-login-password",false);
        alice=new Actor(a,identity.spaces(a).getFirst().id());bob=new Actor(b,identity.spaces(b).getFirst().id());
    }
    @AfterEach void close() {if(pool!=null)pool.close();}
    private UUID storage(boolean ready) throws Exception {
        UUID id=workspaces.create(alice,UUID.randomUUID(),hash("storage"),"R32 workspace").id();
        if(ready) {
            // DB-only fixture: no physical allocation is claimed by this test.
            sql("UPDATE pf.workspace_operation SET status='COMMITTED',effect_state='ALLOCATED' WHERE workspace_id='"+id+"'");
            sql("UPDATE pf.workspace SET state='READY' WHERE id='"+id+"'");
        }
        return id;
    }
    private EnvironmentPreparationRepository.Submission prepare(UUID workspace,UUID request,long version,boolean retry) {
        return environments.prepare(alice,workspace,request,hash((retry?"RETRY":"PREPARE")+"\n"+workspace+"\n"+version),version,retry);
    }
    @Test void absentReadAllocatesNothingAndStorageReadinessIsSeparate() throws Exception {
        UUID workspace=storage(true);
        var snapshot=environments.get(alice,workspace);
        assertEquals("NOT_CREATED",snapshot.state());assertEquals(0,snapshot.version());assertNull(snapshot.environmentId());
        assertEquals(0,count("SELECT count(*) FROM pf.workspace_environment"));
        assertEquals("READY",workspaces.get(alice,workspace).state());
        assertEquals(0,count("SELECT count(*) FROM pf.workspace_sandbox_slot WHERE environment_id IS NOT NULL"));
    }
    @Test void storageMustBeReadyAndAbsentRetryIsRejected() throws Exception {
        UUID workspace=storage(false);
        assertEquals("WORKSPACE_STORAGE_NOT_READY",assertThrows(WorkspaceFailure.class,()->prepare(workspace,UUID.randomUUID(),0,false)).code());
        assertEquals(0,count("SELECT count(*) FROM pf.workspace_environment_outbox"));
        sql("UPDATE pf.workspace SET state='READY' WHERE id='"+workspace+"'");
        assertEquals("ENVIRONMENT_RETRY_UNSAFE",assertThrows(WorkspaceFailure.class,()->prepare(workspace,UUID.randomUUID(),0,true)).code());
    }
    @Test void lostResponseKeepsOriginalOperationBeforeVersionCheckAndRejectsChangedContent() throws Exception {
        UUID workspace=storage(true),key=UUID.randomUUID();
        var first=prepare(workspace,key,0,false);var again=prepare(workspace,key,0,false);
        assertEquals(first.operationId(),again.operationId());assertEquals(1,again.environment().version());
        assertEquals("PREPARING",again.environment().state());assertEquals("READY",workspaces.get(alice,workspace).state());
        assertEquals("PREPARING",workspaces.get(alice,workspace).environmentState());
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(WorkspaceFailure.class,()->prepare(workspace,key,1,false)).code());
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_environment_operation"));
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_environment_outbox"));
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_environment_event"));
    }
    @Test void concurrentSameRequestHasOneReservationAndCommand() throws Exception {
        UUID workspace=storage(true),key=UUID.randomUUID();
        try(var executor=Executors.newFixedThreadPool(4)) {
            var futures=new ArrayList<Future<UUID>>();
            for(int i=0;i<4;i++)futures.add(executor.submit(()->prepare(workspace,key,0,false).operationId()));
            var ids=new HashSet<UUID>();for(var result:futures)ids.add(result.get(15,TimeUnit.SECONDS));assertEquals(1,ids.size());
        }
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_environment"));
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_sandbox_slot WHERE environment_id IS NOT NULL"));
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_environment_idempotency"));
    }
    @Test void outerFailureRollsBackEnvironmentSlotOutboxAndProjection() throws Exception {
        UUID workspace=storage(true);
        assertThrows(IllegalStateException.class,()->transactions.execute(status->{prepare(workspace,UUID.randomUUID(),0,false);throw new IllegalStateException("Injected admission rollback");}));
        for(String table:List.of("workspace_environment","workspace_environment_operation","workspace_environment_outbox","workspace_environment_idempotency","workspace_environment_event"))
            assertEquals(0,count("SELECT count(*) FROM pf."+table));
        assertEquals(0,count("SELECT count(*) FROM pf.workspace_sandbox_slot WHERE environment_id IS NOT NULL"));
        assertEquals("NOT_CREATED",workspaces.get(alice,workspace).environmentState());
    }
    @Test void preparingOrUnknownCannotBeBlindlyRetried() throws Exception {
        UUID workspace=storage(true);var first=prepare(workspace,UUID.randomUUID(),0,false);
        assertEquals("ENVIRONMENT_RETRY_UNSAFE",assertThrows(WorkspaceFailure.class,()->prepare(workspace,UUID.randomUUID(),1,true)).code());
        sql("UPDATE pf.workspace_environment SET state='BLOCKED' WHERE id='"+first.environment().environmentId()+"'");
        sql("UPDATE pf.workspace_environment_operation SET status='UNKNOWN',effect_state='MAY_HAVE_STARTED'");
        assertEquals("ENVIRONMENT_RETRY_UNSAFE",assertThrows(WorkspaceFailure.class,()->prepare(workspace,UUID.randomUUID(),1,true)).code());
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_sandbox_slot WHERE environment_id IS NOT NULL"));
    }
    @Test void definitePreEffectFailureUsesNewGenerationAndKeepsHistory() throws Exception {
        UUID workspace=storage(true);var original=prepare(workspace,UUID.randomUUID(),0,false);
        sql("UPDATE pf.workspace_environment SET state='ERROR'");
        sql("UPDATE pf.workspace_environment_operation SET status='FAILED',effect_state='NOT_STARTED'");
        var retried=prepare(workspace,UUID.randomUUID(),1,true);
        assertEquals(2,retried.environment().generation());assertEquals(2,retried.environment().version());
        assertNotEquals(original.operationId(),retried.operationId());
        assertEquals(2,count("SELECT count(*) FROM pf.workspace_environment_operation"));
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_sandbox_slot WHERE generation=2"));
    }
    @Test void liveMembershipAndAdministratorCannotBypassSpace() throws Exception {
        UUID workspace=storage(true);
        var forged=new Actor(alice.userId(),bob.spaceId());
        assertEquals(404,assertThrows(WorkspaceFailure.class,()->environments.get(forged,workspace)).status());
        assertEquals(404,assertThrows(WorkspaceFailure.class,()->environments.get(bob,workspace)).status());
        sql("UPDATE pf.membership SET status='REVOKED' WHERE user_id='"+alice.userId()+"'");
        assertEquals(404,assertThrows(WorkspaceFailure.class,()->prepare(workspace,UUID.randomUUID(),0,false)).status());
        assertEquals(0,count("SELECT count(*) FROM pf.workspace_environment"));
    }
    @Test void readyNeedsPhysicalReadinessFacts() throws Exception {
        UUID workspace=storage(true);prepare(workspace,UUID.randomUUID(),0,false);
        assertThrows(java.sql.SQLException.class,()->sql("UPDATE pf.workspace_environment SET state='READY'"));
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_environment WHERE state='PREPARING'"));
        assertEquals(1,count("SELECT schema_version FROM pf.baseline_version WHERE id=1"));
    }
    @Test void freshInstallReachesV11AndPreservesRuntimeBaseline() throws Exception {
        flyway(null).clean();flyway(null).migrate();
        assertEquals(11,count("SELECT max(version::int) FROM pf.flyway_schema_history WHERE success"));
        assertEquals(1,count("SELECT schema_version FROM pf.baseline_version WHERE id=1"));
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_sandbox_slot WHERE environment_id IS NULL"));
    }
    private com.agentplatform.domain.workspace.EnvironmentExecutionData.Command claimEnvironment() throws Exception {
        UUID workspace=storage(true);prepare(workspace,UUID.randomUUID(),0,false);
        var publication=executions.publications(UUID.randomUUID()).getFirst();
        return executions.claim(publication,UUID.randomUUID());
    }
    private com.agentplatform.domain.workspace.EnvironmentExecutionData.Receipt ready(com.agentplatform.domain.workspace.EnvironmentExecutionData.Command command) {
        // Synthetic trusted-runner fact for transaction tests; native runtime is tested separately.
        return new com.agentplatform.domain.workspace.EnvironmentExecutionData.Receipt(command.commandId(),command.operationId(),command.environmentId(),command.workspaceId(),
                command.generation(),command.fence(),"COMMITTED","READY",null,
                new SandboxRuntime.Container("c".repeat(64),command.imageId(),"2026-10-22T00:00:00Z",true,command.policyHash(),"d".repeat(64)),null,1);
    }
    @Test void twoClaimersCommitOneOwnerAndInboxWithoutLeaseStealing() throws Exception {
        UUID workspace=storage(true);prepare(workspace,UUID.randomUUID(),0,false);
        var publication=executions.publications(UUID.randomUUID()).getFirst();
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->executions.claim(publication,UUID.randomUUID()));
            var second=executor.submit(()->executions.claim(publication,UUID.randomUUID()));
            assertEquals(1,java.util.stream.Stream.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS)).filter(Objects::nonNull).count());
        }
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_environment_inbox"));
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_environment_operation WHERE owner_id IS NOT NULL AND status='CLAIMED'"));
        sql("UPDATE pf.workspace_environment_operation SET lease_until=clock_timestamp()-interval '1 second'");
        assertNull(executions.claim(publication,UUID.randomUUID()));
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_sandbox_slot WHERE environment_id IS NOT NULL"));
    }
    @Test void runnerDedupAndDurableReceiptPrecedeReadinessProjection() throws Exception {
        var command=claimEnvironment();executions.beforeCall(command);
        assertNull(executions.accept(command,hash("request")));
        assertEquals("RUNNING",executions.accept(command,hash("request")).state());
        assertEquals("RUNNER_COMMAND_CONFLICT",assertThrows(WorkspaceFailure.class,()->executions.accept(command,hash("changed"))).code());
        var receipt=ready(command);executions.commitReceipt(command,receipt);executions.commitReceipt(command,receipt);
        assertEquals("PREPARING",environments.get(alice,command.workspaceId()).state());
        assertEquals(receipt,executions.receipt(command));executions.complete(command,receipt);
        assertEquals("READY",environments.get(alice,command.workspaceId()).state());
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_environment_receipt"));
        assertEquals(2,count("SELECT count(*) FROM pf.workspace_environment_event"));
    }
    @Test void commitTimeLeaseGuardRollsBackRunnerReceipt() throws Exception {
        var command=claimEnvironment();executions.beforeCall(command);executions.accept(command,hash("request"));
        assertThrows(WorkspaceFailure.class,()->transactions.execute(status->{
            executions.commitReceipt(command,ready(command));
            new org.springframework.jdbc.core.JdbcTemplate(pool).update("UPDATE pf.workspace_environment_operation SET lease_until=clock_timestamp()-interval '1 second'");return null;
        }));
        assertEquals(0,count("SELECT count(*) FROM pf.workspace_environment_receipt"));
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_environment_command WHERE state='RUNNING' AND result_revision=0"));
    }
    @Test void unknownReceiptBlocksAndRetainsCapacityWithoutAnotherClaim() throws Exception {
        var command=claimEnvironment();executions.beforeCall(command);executions.accept(command,hash("request"));
        var receipt=new com.agentplatform.domain.workspace.EnvironmentExecutionData.Receipt(command.commandId(),command.operationId(),command.environmentId(),command.workspaceId(),
                command.generation(),command.fence(),"UNKNOWN","MAY_HAVE_STARTED","SANDBOX_RESULT_UNKNOWN",null,null,1);
        executions.commitReceipt(command,receipt);executions.complete(command,receipt);
        assertEquals("BLOCKED",environments.get(alice,command.workspaceId()).state());
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_sandbox_slot WHERE environment_id IS NOT NULL"));
        assertEquals("ENVIRONMENT_RETRY_UNSAFE",assertThrows(WorkspaceFailure.class,()->prepare(command.workspaceId(),UUID.randomUUID(),2,true)).code());
    }
    @Test void revokedPendingPreparationIsDurablyRejectedBeforeAck() throws Exception {
        UUID workspace=storage(true);prepare(workspace,UUID.randomUUID(),0,false);
        var publication=executions.publications(UUID.randomUUID()).getFirst();
        sql("UPDATE pf.membership SET status='REVOKED' WHERE user_id='"+alice.userId()+"'");
        assertNull(executions.claim(publication,UUID.randomUUID()));
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_environment_operation WHERE status='FAILED' AND effect_state='NOT_STARTED'"));
        assertEquals(0,count("SELECT count(*) FROM pf.workspace_environment_command"));
        assertEquals(0,count("SELECT count(*) FROM pf.workspace_sandbox_slot WHERE environment_id IS NOT NULL"));
    }
}
