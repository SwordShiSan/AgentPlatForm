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
    private EnvironmentMaintenanceRepository maintenance;
    private RunnerInstance runner;
    private RunnerStopProofRepository runnerProofs;
    private FileHelperRepository fileHelpers;
    private EnvironmentUseRepository uses;
    private Actor alice, bob;
    private static Flyway flyway(String target) {
        var configuration = Flyway.configure().dataSource(url,"postgres",password).defaultSchema("pf")
                .schemas("pf","pf_runtime").cleanDisabled(false);
        if (target != null) configuration.target(target);
        return configuration.load();
    }
    @BeforeAll static void migrate() throws Exception {
        boolean r34="1".equals(System.getenv("R34_ACCEPTANCE"));
        url = System.getenv(r34?"R34_TEST_JDBC_URL":"R32_TEST_JDBC_URL");
        Assumptions.assumeTrue(url != null,"R32 PostgreSQL not selected; NOT_RUN");
        assertEquals("1",System.getenv(r34?"R34_ACCEPTANCE":"R32_ACCEPTANCE"));
        assertTrue(url.startsWith(r34?"jdbc:postgresql://127.0.0.1:25434/environment_test?":"jdbc:postgresql://127.0.0.1:15449/environment_test?"));
        password = Files.readString(Path.of(System.getenv(r34?"R34_TEST_PASSWORD_FILE":"R32_TEST_PASSWORD_FILE"))).strip();
        flyway(null).clean();
        flyway("10").migrate();
        assertEquals(10,count("SELECT max(version::int) FROM pf.flyway_schema_history WHERE success"));
        flyway(null).migrate();
        assertEquals(14,count("SELECT max(version::int) FROM pf.flyway_schema_history WHERE success"));
        assertEquals(1,count("SELECT schema_version FROM pf.baseline_version WHERE id=1"));
    }
    private static void sql(String text) throws Exception {
        try(var connection=DriverManager.getConnection(url,"postgres",password);var statement=connection.createStatement()) {statement.execute(text);}
    }
    private static long count(String text) throws Exception {
        try(var connection=DriverManager.getConnection(url,"postgres",password);var statement=connection.createStatement();var rows=statement.executeQuery(text)) {rows.next();return rows.getLong(1);}
    }
    private static String hash(String text) {return RunnerSignatures.sha256(text.getBytes(StandardCharsets.UTF_8));}
    private SqlSessionTemplate session(javax.sql.DataSource source) throws Exception {
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);
        var configuration=new org.apache.ibatis.session.Configuration();configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);configuration.setCacheEnabled(false);
        configuration.getTypeHandlerRegistry().register(new UuidTypeHandler());factory.setConfiguration(configuration);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mybatis/mapper/workspace/*.xml"));
        return new SqlSessionTemplate(factory.getObject());
    }
    @BeforeEach void setup() throws Exception {
        sql("TRUNCATE pf.app_user CASCADE");
        sql("TRUNCATE pf.workspace_runner_instance CASCADE");
        sql("UPDATE pf.workspace_capacity SET reserved_bytes=0,reserved_slots=0,next_project_id=200001");
        sql("INSERT INTO pf.workspace_sandbox_slot(id) VALUES(1) ON CONFLICT(id) DO UPDATE SET space_id=NULL,workspace_id=NULL,environment_id=NULL,generation=NULL");
        var configuration=new com.zaxxer.hikari.HikariConfig();configuration.setJdbcUrl(url);configuration.setUsername("postgres");configuration.setPassword(password);
        configuration.setMaximumPoolSize(4);configuration.setMinimumIdle(1);
        pool=new com.zaxxer.hikari.HikariDataSource(configuration);
        transactions=new TransactionTemplate(new JdbcTransactionManager(pool));
        var session=session(pool);var mapper=session.getMapper(WorkspaceMapper.class);
        workspaces=new TransactionalWorkspaceRepository(new MyBatisWorkspaceRepository(mapper),transactions);
        environments=new TransactionalEnvironmentPreparationRepository(new MyBatisEnvironmentPreparationRepository(
                session.getMapper(EnvironmentPreparationMapper.class),mapper,"sha256:"+"a".repeat(64),"b".repeat(64)),transactions);
        executions=new TransactionalEnvironmentExecutionRepository(new MyBatisEnvironmentExecutionRepository(
                session.getMapper(EnvironmentExecutionMapper.class),session.getMapper(EnvironmentPreparationMapper.class),mapper),transactions);
        var useMapper=session.getMapper(EnvironmentUseMapper.class);var preparationMapper=session.getMapper(EnvironmentPreparationMapper.class);
        var executionMapper=session.getMapper(EnvironmentExecutionMapper.class);
        fileHelpers=new TransactionalFileHelperRepository(new MyBatisFileHelperRepository(session.getMapper(FileHelperMapper.class),useMapper,
                preparationMapper,executionMapper,mapper),transactions);
        uses=new TransactionalEnvironmentUseRepository(new MyBatisEnvironmentUseRepository(useMapper,preparationMapper,executionMapper,mapper,fileHelpers),transactions);
        runner=new RunnerInstance(UUID.randomUUID(),"e".repeat(64),"sha256:"+"f".repeat(64),"2026-10-22T00:00:00.123456789Z");
        executions.registerRunner(runner);
        runnerProofs=new TransactionalRunnerStopProofRepository(new MyBatisRunnerStopProofRepository(
                session.getMapper(RunnerStopProofMapper.class),session.getMapper(EnvironmentPreparationMapper.class)),transactions);
        maintenance=new TransactionalEnvironmentMaintenanceRepository(new MyBatisEnvironmentMaintenanceRepository(
                session.getMapper(EnvironmentMaintenanceMapper.class),session.getMapper(EnvironmentPreparationMapper.class),session.getMapper(EnvironmentExecutionMapper.class),mapper),transactions);
        var identity=IdentityPersistenceFixture.create(pool);
        UUID a=identity.provision(UUID.randomUUID(),"alice","alice","not-a-login-password",true);
        UUID b=identity.provision(UUID.randomUUID(),"bob","bob","not-a-login-password",false);
        alice=new Actor(a,identity.spaces(a).getFirst().id());bob=new Actor(b,identity.spaces(b).getFirst().id());
    }
    @AfterEach void close() {if(pool!=null)pool.close();}
    private com.agentplatform.domain.workspace.EnvironmentExecutionData.Receipt accept(com.agentplatform.domain.workspace.EnvironmentExecutionData.Command command,String hash) {
        return executions.accept(command,hash,runner.id());
    }
    private void commitReceipt(com.agentplatform.domain.workspace.EnvironmentExecutionData.Command command,com.agentplatform.domain.workspace.EnvironmentExecutionData.Receipt receipt) {
        executions.commitReceipt(command,receipt,runner.id());
    }
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
    @Test void freshInstallReachesCurrentMigrationAndPreservesRuntimeBaseline() throws Exception {
        flyway(null).clean();flyway(null).migrate();
        assertEquals(14,count("SELECT max(version::int) FROM pf.flyway_schema_history WHERE success"));
        assertEquals(1,count("SELECT schema_version FROM pf.baseline_version WHERE id=1"));
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_sandbox_slot WHERE environment_id IS NULL"));
    }
    private com.agentplatform.domain.workspace.EnvironmentExecutionData.Command claimEnvironment() throws Exception {
        UUID workspace=storage(true);prepare(workspace,UUID.randomUUID(),0,false);
        var publication=executions.publications(UUID.randomUUID()).getFirst();
        return executions.claim(publication,UUID.randomUUID());
    }

    @Test void v13UpgradePreservesAcceptedV2IdentityWithoutInventingLifecycleFacts() throws Exception {
        flyway(null).clean();flyway("13").migrate();
        var identity=IdentityPersistenceFixture.create(pool);
        UUID user=identity.provision(UUID.randomUUID(),"upgrade-user","upgrade-user","not-a-login-password",false);
        var actor=new Actor(user,identity.spaces(user).getFirst().id());
        UUID workspace=workspaces.create(actor,UUID.randomUUID(),hash("upgrade-storage"),"V13 storage fixture").id();
        sql("UPDATE pf.workspace_operation SET status='COMMITTED',effect_state='ALLOCATED' WHERE workspace_id='"+workspace+"'");
        sql("UPDATE pf.workspace SET state='READY' WHERE id='"+workspace+"'");
        environments.prepare(actor,workspace,UUID.randomUUID(),hash("historical-v2"),0,false);
        var command=executions.claim(executions.publications(UUID.randomUUID()).getFirst(),UUID.randomUUID());
        executions.registerRunner(runner);
        assertNull(executions.accept(command,hash("historical-v2-command"),runner.id()));
        flyway(null).migrate();
        assertEquals(14,count("SELECT max(version::int) FROM pf.flyway_schema_history WHERE success"));
        assertEquals(command,executions.lookup(command.operationId()));
        assertEquals("ENVIRONMENT_V2",command.protocol());
        assertEquals(1,count("SELECT count(*) FROM pf.app_user WHERE id='"+user+"'"));
        assertEquals(1,count("SELECT count(*) FROM pf.workspace WHERE id='"+workspace+"'"));
        assertEquals(1,count("SELECT schema_version FROM pf.baseline_version WHERE id=1"));
        assertEquals(0,count("SELECT count(*) FROM pf.workspace_environment_cleanup"));
        assertThrows(java.sql.SQLException.class,()->sql("UPDATE pf.workspace_environment_operation SET protocol='ENVIRONMENT_V3' WHERE id='"+command.operationId()+"'"));
        assertEquals(command,executions.lookup(command.operationId()));
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
        assertNull(accept(command,hash("request")));
        assertEquals("RUNNING",accept(command,hash("request")).state());
        assertEquals("RUNNER_COMMAND_CONFLICT",assertThrows(WorkspaceFailure.class,()->accept(command,hash("changed"))).code());
        var receipt=ready(command);commitReceipt(command,receipt);commitReceipt(command,receipt);
        assertEquals("PREPARING",environments.get(alice,command.workspaceId()).state());
        assertEquals(receipt,executions.receipt(command));executions.complete(command,receipt);
        assertEquals("READY",environments.get(alice,command.workspaceId()).state());
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_environment_receipt"));
        assertEquals(2,count("SELECT count(*) FROM pf.workspace_environment_event"));
    }
    @Test void commitTimeLeaseGuardRollsBackRunnerReceipt() throws Exception {
        var command=claimEnvironment();executions.beforeCall(command);accept(command,hash("request"));
        assertThrows(WorkspaceFailure.class,()->transactions.execute(status->{
            commitReceipt(command,ready(command));
            new org.springframework.jdbc.core.JdbcTemplate(pool).update("UPDATE pf.workspace_environment_operation SET lease_until=clock_timestamp()-interval '1 second'");return null;
        }));
        assertEquals(0,count("SELECT count(*) FROM pf.workspace_environment_receipt"));
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_environment_command WHERE state='RUNNING' AND result_revision=0"));
    }
    @Test void unknownReceiptBlocksAndRetainsCapacityWithoutAnotherClaim() throws Exception {
        var command=claimEnvironment();executions.beforeCall(command);accept(command,hash("request"));
        var receipt=new com.agentplatform.domain.workspace.EnvironmentExecutionData.Receipt(command.commandId(),command.operationId(),command.environmentId(),command.workspaceId(),
                command.generation(),command.fence(),"UNKNOWN","MAY_HAVE_STARTED","SANDBOX_RESULT_UNKNOWN",null,null,1);
        commitReceipt(command,receipt);executions.complete(command,receipt);
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
    @Test void expirationBlocksWithoutReplacingOwnerOrFreeingSlot() throws Exception {
        var command=claimEnvironment();
        assertEquals(0,maintenance.blockExpired());
        sql("UPDATE pf.workspace_environment_operation SET lease_until=clock_timestamp()-interval '1 second'");
        assertEquals(1,maintenance.blockExpired());assertEquals(0,maintenance.blockExpired());
        assertEquals("BLOCKED",environments.get(alice,command.workspaceId()).state());
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_environment_operation WHERE owner_id='"+command.ownerId()+"' AND status='UNKNOWN'"));
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_sandbox_slot WHERE environment_id IS NOT NULL"));
        assertEquals("ENVIRONMENT_STALE_OWNER",assertThrows(WorkspaceFailure.class,()->executions.renew(command)).code());
    }
    private com.agentplatform.domain.workspace.EnvironmentExecutionData.Command readyEnvironment() throws Exception {
        var command=claimEnvironment();executions.beforeCall(command);accept(command,hash("prepare"));
        var receipt=ready(command);commitReceipt(command,receipt);executions.complete(command,receipt);return command;
    }
    @Test void idleStopIsDurableAndReleasesOnlyAfterExactStopReceipt() throws Exception {
        var original=readyEnvironment();assertEquals(0,maintenance.scheduleStop());
        sql("UPDATE pf.workspace_environment SET last_activity_at=clock_timestamp()-interval '61 seconds'");
        assertEquals(1,maintenance.scheduleStop());assertEquals(0,maintenance.scheduleStop());
        var stopping=environments.get(alice,original.workspaceId());
        assertEquals("STOP",stopping.operationKind());assertEquals("PENDING",stopping.operationStatus());assertEquals("READY",stopping.state());
        var publication=executions.publications(UUID.randomUUID()).stream().filter(p->p.operationId().equals(stopping.operationId())).findFirst().orElseThrow();
        var command=executions.claim(publication,UUID.randomUUID());
        assertEquals(original.physicalIdentity(),command.physicalIdentity());assertEquals(2,command.fence());
        assertNotEquals(original.commandId(),command.commandId());
        executions.beforeCall(command);accept(command,hash("stop"));
        var stop=new SandboxRuntime.Stop("c".repeat(64),command.imageId(),command.containerStartedAt(),"2026-10-22T00:00:05Z",true,true,java.time.Instant.now());
        var receipt=new com.agentplatform.domain.workspace.EnvironmentExecutionData.Receipt(command.commandId(),command.operationId(),command.environmentId(),command.workspaceId(),
                command.generation(),command.fence(),"COMMITTED","STOPPED",null,null,stop,1);
        commitReceipt(command,receipt);
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_sandbox_slot WHERE environment_id IS NOT NULL"));
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_environment_stop_receipt"));
        executions.complete(command,receipt);
        assertEquals("STOPPED",environments.get(alice,command.workspaceId()).state());
        assertEquals(0,count("SELECT count(*) FROM pf.workspace_sandbox_slot WHERE environment_id IS NOT NULL"));
        var rebuilt=prepare(command.workspaceId(),UUID.randomUUID(),4,false);
        assertEquals(2,rebuilt.environment().generation());assertEquals(5,rebuilt.environment().version());
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_environment_stop_receipt"));
    }
    @Test void revocationDoesNotPreventPrivilegeReducingStop() throws Exception {
        var original=readyEnvironment();
        sql("UPDATE pf.membership SET status='REVOKED' WHERE user_id='"+alice.userId()+"'");
        assertEquals(1,maintenance.scheduleStop());
        var item=executions.publications(UUID.randomUUID()).stream().filter(p->!p.operationId().equals(original.operationId())).findFirst().orElseThrow();
        var stop=executions.claim(item,UUID.randomUUID());assertNotNull(stop);assertEquals("STOP",stop.kind());
        assertDoesNotThrow(()->executions.check(stop));
    }
    @Test void aNewRunnerCannotAssertOldCallbacksStoppedWithoutHostProof() throws Exception {
        var command=claimEnvironment();executions.beforeCall(command);accept(command,hash("prepare"));
        var replacement=new RunnerInstance(UUID.randomUUID(),"1".repeat(64),runner.imageId(),"2026-10-22T00:00:05Z");
        executions.registerRunner(replacement);
        assertEquals("RUNNING",executions.accept(command,hash("prepare"),replacement.id()).state());
        assertEquals("RUNNER_INSTANCE_STOP_UNPROVEN",assertThrows(WorkspaceFailure.class,()->executions.checkRunner(command,replacement.id())).code());
        assertEquals("RUNNER_INSTANCE_STOP_UNPROVEN",assertThrows(WorkspaceFailure.class,()->executions.commitReceipt(command,ready(command),replacement.id())).code());
        assertEquals(0,count("SELECT count(*) FROM pf.workspace_environment_receipt"));
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_sandbox_slot WHERE environment_id IS NOT NULL"));
        assertDoesNotThrow(()->executions.checkRunner(command,runner.id()));
    }
    @Test void runnerIdentityBindsExactContainerAndNanosecondStart() {
        assertDoesNotThrow(()->executions.registerRunner(runner));
        var forged=new RunnerInstance(runner.id(),"1".repeat(64),runner.imageId(),runner.startedAt());
        assertEquals(409,assertThrows(WorkspaceFailure.class,()->executions.registerRunner(forged)).status());
        var sameStart=new RunnerInstance(UUID.randomUUID(),runner.containerId(),runner.imageId(),runner.startedAt());
        assertEquals(409,assertThrows(WorkspaceFailure.class,()->executions.registerRunner(sameStart)).status());
        var freshStart=new RunnerInstance(UUID.randomUUID(),runner.containerId(),runner.imageId(),"2026-10-22T00:00:00.123456790Z");
        assertDoesNotThrow(()->executions.registerRunner(freshStart));
    }
    @Test void verifiedHostEvidenceEnablesOnlyQueryConvergenceAndNeverReleasesCapacity() throws Exception {
        var command=claimEnvironment();executions.beforeCall(command);accept(command,hash("prepare"));
        var replacement=new RunnerInstance(UUID.randomUUID(),"1".repeat(64),runner.imageId(),"2026-10-22T00:00:05Z");
        executions.registerRunner(replacement);
        var now=java.time.Instant.now();var proof=new StoppedRunner(runner,now.minusSeconds(2),now,hash("verified-host-fixture"));
        runnerProofs.record(proof);runnerProofs.record(proof);
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_runner_stop_proof"));
        assertEquals("RUNNER_INSTANCE_STOP_UNPROVEN",assertThrows(WorkspaceFailure.class,()->executions.checkRunner(command,runner.id())).code());
        assertDoesNotThrow(()->executions.checkRunner(command,replacement.id()));
        assertEquals("RUNNER_INSTANCE_STOPPED",assertThrows(WorkspaceFailure.class,()->executions.registerRunner(runner)).code());
        assertEquals("RUNNING",executions.receipt(command).state());
        assertEquals(0,count("SELECT count(*) FROM pf.workspace_environment_receipt"));
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_sandbox_slot WHERE environment_id IS NOT NULL"));
    }
    @Test void hostEvidenceMismatchAndExpiredObservationAreRejected() {
        var now=java.time.Instant.now();
        var wrong=new RunnerInstance(runner.id(),"1".repeat(64),runner.imageId(),runner.startedAt());
        assertEquals("STOPPED_RUNNER_IDENTITY_MISMATCH",assertThrows(WorkspaceFailure.class,()->runnerProofs.record(
                new StoppedRunner(wrong,now.minusSeconds(2),now,hash("wrong")))).code());
        assertEquals("STOPPED_RUNNER_PROOF_EXPIRED",assertThrows(WorkspaceFailure.class,()->runnerProofs.record(
                new StoppedRunner(runner,now.minusSeconds(602),now.minusSeconds(601),hash("stale")))).code());
    }
    private org.springframework.amqp.rabbit.connection.CachingConnectionFactory broker() throws Exception {
        String file=System.getenv("R32_TEST_MQ_PASSWORD_FILE");Assumptions.assumeTrue(file!=null,"R32 real MQ not selected; NOT_RUN");
        var connections=new org.springframework.amqp.rabbit.connection.CachingConnectionFactory("127.0.0.1",15699);
        connections.setPublisherConfirmType(org.springframework.amqp.rabbit.connection.CachingConnectionFactory.ConfirmType.CORRELATED);
        connections.setPublisherReturns(true);connections.setUsername("r32");connections.setPassword(Files.readString(Path.of(file)).strip());
        connections.setVirtualHost("/agentplatform");connections.setConnectionTimeout(2000);
        var admin=new org.springframework.amqp.rabbit.core.RabbitAdmin(connections);
        var exchange=new org.springframework.amqp.core.DirectExchange(com.agentplatform.infrastructure.workspace.SpringEnvironmentMessaging.EXCHANGE,true,false);
        var queue=new org.springframework.amqp.core.Queue(com.agentplatform.infrastructure.workspace.SpringEnvironmentMessaging.QUEUE,true);
        admin.declareExchange(exchange);admin.declareQueue(queue);
        admin.declareBinding(org.springframework.amqp.core.BindingBuilder.bind(queue).to(exchange).with(com.agentplatform.infrastructure.workspace.SpringEnvironmentMessaging.ROUTE));
        admin.purgeQueue(queue.getName());return connections;
    }
    @Test void actualMqTwoConsumersAndDuplicatePublicationKeepOneOwner() throws Exception {
        UUID workspace=storage(true);prepare(workspace,UUID.randomUUID(),0,false);
        var item=executions.publications(UUID.randomUUID()).getFirst();var connections=broker();
        var delivered=new CountDownLatch(2);var executed=new CountDownLatch(1);var effects=new java.util.concurrent.atomic.AtomicInteger();
        try(var first=new com.agentplatform.infrastructure.workspace.SpringEnvironmentMessaging(connections,executions);
                var second=new com.agentplatform.infrastructure.workspace.SpringEnvironmentMessaging(connections,executions)) {
            for(var messaging:List.of(first,second)) {
                UUID owner=UUID.randomUUID();
                messaging.start(new com.agentplatform.infrastructure.workspace.SpringEnvironmentMessaging.Handoff() {
                    @Override public com.agentplatform.domain.workspace.EnvironmentExecutionData.Command claim(com.agentplatform.domain.workspace.EnvironmentExecutionData.Publication publication) {
                        var command=executions.claim(publication,owner);delivered.countDown();
                        assertDoesNotThrow(()->assertTrue(delivered.await(10,TimeUnit.SECONDS)));return command;
                    }
                    @Override public void execute(com.agentplatform.domain.workspace.EnvironmentExecutionData.Command command) {effects.incrementAndGet();executed.countDown();}
                });
            }
            first.publish(item);second.publish(item);
            assertTrue(delivered.await(15,TimeUnit.SECONDS));assertTrue(executed.await(15,TimeUnit.SECONDS));assertEquals(1,effects.get());
            assertEquals(1,count("SELECT count(*) FROM pf.workspace_environment_inbox"));
            assertEquals(1,count("SELECT count(*) FROM pf.workspace_environment_operation WHERE owner_id IS NOT NULL"));
        } finally {connections.destroy();}
    }
    @Test void actualMqClaimCommittedButAckLostDoesNotExecuteAgain() throws Exception {
        UUID workspace=storage(true);prepare(workspace,UUID.randomUUID(),0,false);
        var item=executions.publications(UUID.randomUUID()).getFirst();var connections=broker();
        var deliveries=new java.util.concurrent.atomic.AtomicInteger();var effects=new java.util.concurrent.atomic.AtomicInteger();var redelivered=new CountDownLatch(1);
        try(var messaging=new com.agentplatform.infrastructure.workspace.SpringEnvironmentMessaging(connections,executions)) {
            messaging.start(new com.agentplatform.infrastructure.workspace.SpringEnvironmentMessaging.Handoff() {
                @Override public com.agentplatform.domain.workspace.EnvironmentExecutionData.Command claim(com.agentplatform.domain.workspace.EnvironmentExecutionData.Publication publication) {
                    var command=executions.claim(publication,UUID.randomUUID());
                    if(deliveries.incrementAndGet()==1) {assertNotNull(command);throw new IllegalStateException("Injected committed handoff before ACK");}
                    assertNull(command);redelivered.countDown();return null;
                }
                @Override public void execute(com.agentplatform.domain.workspace.EnvironmentExecutionData.Command command) {effects.incrementAndGet();}
            });
            messaging.publish(item);assertTrue(redelivered.await(20,TimeUnit.SECONDS));assertEquals(0,effects.get());
            assertEquals(1,count("SELECT count(*) FROM pf.workspace_environment_inbox"));
            assertEquals(1,count("SELECT count(*) FROM pf.workspace_sandbox_slot WHERE environment_id IS NOT NULL"));
        } finally {connections.destroy();}
    }
    @Test void actualMqRollbackBeforeClaimCommitIsRedeliveredWithoutPartialOwner() throws Exception {
        UUID workspace=storage(true);prepare(workspace,UUID.randomUUID(),0,false);
        var item=executions.publications(UUID.randomUUID()).getFirst();var connections=broker();
        var attempts=new java.util.concurrent.atomic.AtomicInteger();var effects=new java.util.concurrent.atomic.AtomicInteger();var completed=new CountDownLatch(1);
        try(var messaging=new com.agentplatform.infrastructure.workspace.SpringEnvironmentMessaging(connections,executions)) {
            messaging.start(new com.agentplatform.infrastructure.workspace.SpringEnvironmentMessaging.Handoff() {
                @Override public com.agentplatform.domain.workspace.EnvironmentExecutionData.Command claim(com.agentplatform.domain.workspace.EnvironmentExecutionData.Publication publication) {
                    if(attempts.incrementAndGet()==1)return transactions.execute(status->{
                        assertNotNull(executions.claim(publication,UUID.randomUUID()));throw new IllegalStateException("Injected before claim commit");
                    });
                    return executions.claim(publication,UUID.randomUUID());
                }
                @Override public void execute(com.agentplatform.domain.workspace.EnvironmentExecutionData.Command command) {effects.incrementAndGet();completed.countDown();}
            });
            messaging.publish(item);assertTrue(completed.await(20,TimeUnit.SECONDS));assertEquals(1,effects.get());
            assertEquals(1,count("SELECT count(*) FROM pf.workspace_environment_inbox"));
        } finally {connections.destroy();}
    }
    @Test void actualMqReturnedPublicationKeepsDurableOutbox() throws Exception {
        UUID workspace=storage(true);prepare(workspace,UUID.randomUUID(),0,false);
        var item=executions.publications(UUID.randomUUID()).getFirst();var connections=broker();
        var binding=new org.springframework.amqp.core.Binding(com.agentplatform.infrastructure.workspace.SpringEnvironmentMessaging.QUEUE,org.springframework.amqp.core.Binding.DestinationType.QUEUE,
                com.agentplatform.infrastructure.workspace.SpringEnvironmentMessaging.EXCHANGE,com.agentplatform.infrastructure.workspace.SpringEnvironmentMessaging.ROUTE,Map.of());
        var admin=new org.springframework.amqp.rabbit.core.RabbitAdmin(connections);admin.removeBinding(binding);
        try(var messaging=new com.agentplatform.infrastructure.workspace.SpringEnvironmentMessaging(connections,executions)) {
            assertThrows(WorkspaceFailure.class,()->messaging.publish(item));executions.published(item,false);
            assertEquals(1,count("SELECT count(*) FROM pf.workspace_environment_outbox WHERE status='PENDING'"));
            assertEquals(0,count("SELECT count(*) FROM pf.workspace_environment_inbox"));
        } finally {admin.declareBinding(binding);connections.destroy();}
    }
    @Test void failedStopWithoutTerminationProofNeverOffersGenerationRetry() throws Exception {
        var original=readyEnvironment();sql("UPDATE pf.workspace_environment SET last_activity_at=clock_timestamp()-interval '61 seconds'");
        assertEquals(1,maintenance.scheduleStop());
        sql("UPDATE pf.workspace_environment_operation SET status='FAILED',effect_state='NOT_STARTED' WHERE kind='STOP'");
        sql("UPDATE pf.workspace_environment SET state='ERROR'");
        var snapshot=environments.get(alice,original.workspaceId());assertFalse(snapshot.prepareAllowed());assertFalse(snapshot.retryAllowed());
        assertEquals("ENVIRONMENT_RETRY_UNSAFE",assertThrows(WorkspaceFailure.class,()->prepare(original.workspaceId(),UUID.randomUUID(),3,true)).code());
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_sandbox_slot WHERE environment_id IS NOT NULL"));
    }
    @Test void actualRunnerAndOperatorRolesHaveOnlyMetadataAndReceiptAuthority() throws Exception {
        for(String role:List.of("ap_api","ap_worker","ap_runner","ap_workspace_ops"))sql("DO $$ BEGIN CREATE ROLE "+role+" NOLOGIN; EXCEPTION WHEN duplicate_object THEN NULL; END $$");
        sql(Files.readString(Path.of(System.getenv("R32_WORKSPACE_GRANTS_FILE"))));
        sql(Files.readString(Path.of(System.getenv("R32_ENVIRONMENT_GRANTS_FILE"))));
        var command=claimEnvironment();executions.beforeCall(command);
        for(String role:List.of("ap_runner","ap_workspace_ops")) {
            var configuration=new com.zaxxer.hikari.HikariConfig();configuration.setJdbcUrl(url);configuration.setUsername("postgres");configuration.setPassword(password);
            configuration.setMaximumPoolSize(2);configuration.setMinimumIdle(1);configuration.setConnectionInitSql("SET ROLE "+role);
            try(var restricted=new com.zaxxer.hikari.HikariDataSource(configuration)) {
                var session=session(restricted);var tx=new TransactionTemplate(new JdbcTransactionManager(restricted));
                if(role.equals("ap_runner")) {
                    var runnerPort=new TransactionalEnvironmentExecutionRepository(new MyBatisEnvironmentExecutionRepository(session.getMapper(EnvironmentExecutionMapper.class),
                            session.getMapper(EnvironmentPreparationMapper.class),session.getMapper(WorkspaceMapper.class)),tx);
                    assertEquals(command,runnerPort.lookup(command.operationId()));runnerPort.registerRunner(runner);
                    assertNull(runnerPort.accept(command,hash("role-bound"),runner.id()));
                    var receipt=ready(command);runnerPort.commitReceipt(command,receipt,runner.id());assertEquals(receipt,runnerPort.receipt(command));
                    for(String query:List.of("UPDATE pf.workspace_environment SET state='READY'","UPDATE pf.workspace_environment_operation SET fence=fence+1",
                            "INSERT INTO pf.workspace_runner_stop_proof(runner_instance_id) VALUES('"+runner.id()+"')"))denied(restricted,query);
                } else {
                    var proofPort=new TransactionalRunnerStopProofRepository(new MyBatisRunnerStopProofRepository(session.getMapper(RunnerStopProofMapper.class),session.getMapper(EnvironmentPreparationMapper.class)),tx);
                    var now=java.time.Instant.now();assertDoesNotThrow(()->proofPort.record(new StoppedRunner(runner,now.minusSeconds(2),now,hash("role-host-fixture"))));
                    denied(restricted,"UPDATE pf.workspace_runner_instance SET container_id='"+"1".repeat(64)+"'");
                }
                for(String table:List.of("pf.app_user","pf.credential","pf.message","pf_runtime.state_slot"))denied(restricted,"SELECT * FROM "+table);
            }
        }
    }
    private EnvironmentUseData.Request probe() {return new EnvironmentUseData.Request(null,SandboxRuntime.Probe.PYTHON_666);}
    @Test void fileHelperLifecycleIsFencedAndStopEvidenceIsImmutable() throws Exception {
        var ready=readyEnvironment();
        var request=new EnvironmentUseData.Request(new SandboxRuntime.FileRequest(SandboxRuntime.FileAction.WRITE,"sample.txt",
                Base64.getEncoder().encodeToString("ok".getBytes(StandardCharsets.UTF_8))),null);
        var command=uses.admit(alice,ready.workspaceId(),UUID.randomUUID(),UUID.randomUUID(),request).command();
        assertTrue(uses.accept(command,runner.id()));
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_environment_use WHERE id='"+command.id()+"' AND file_execution_mode='TRUSTED_HELPER'"));
        var intent=new FileHelperData.Intent(command.id(),"sha256:"+"c".repeat(64),command.policyHash(),"ap-file-helper-"+command.id());
        fileHelpers.intent(command,runner.id(),intent);
        var created=new FileHelperData.Physical("d".repeat(64),intent.imageId(),null,null);
        fileHelpers.created(command,runner.id(),created);fileHelpers.requestStart(command,runner.id());
        String started=java.time.Instant.now().minusSeconds(2).toString();
        var running=new FileHelperData.Physical(created.containerId(),intent.imageId(),started,null);
        fileHelpers.started(command,runner.id(),running);
        var executing=new FileHelperData.Physical(created.containerId(),intent.imageId(),started,"e".repeat(64));
        fileHelpers.recordExec(command,runner.id(),executing);
        assertEquals("RUNNING",uses.receipt(command).state());
        var stop=new FileHelperData.Stopped(executing,intent.imageId(),false,java.time.Instant.now().minusMillis(500).toString(),true,true,
                java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        fileHelpers.stopped(command,runner.id(),stop);
        assertEquals(stop,fileHelpers.retirement(command));
        assertEquals(executing,fileHelpers.find(command,runner.id()).physical());
        var different=new FileHelperData.Physical(created.containerId(),intent.imageId(),started,"f".repeat(64));
        assertEquals("FILE_HELPER_STOPPED",assertThrows(WorkspaceFailure.class,()->fileHelpers.recordExec(command,runner.id(),different)).code());
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_file_helper_stop WHERE use_id='"+command.id()+"'"));
    }
    @Test void missingFileHelperCanOnlyRecordNeverStartedBeforeAnyStartRequest() throws Exception {
        var ready=readyEnvironment();
        var request=new EnvironmentUseData.Request(new SandboxRuntime.FileRequest(SandboxRuntime.FileAction.READ,"sample.txt",null),null);
        var command=uses.admit(alice,ready.workspaceId(),UUID.randomUUID(),UUID.randomUUID(),request).command();uses.accept(command,runner.id());
        var intent=new FileHelperData.Intent(command.id(),"sha256:"+"c".repeat(64),command.policyHash(),"ap-file-helper-"+command.id());
        fileHelpers.intent(command,runner.id(),intent);
        var never=new FileHelperData.Stopped(null,intent.imageId(),true,null,true,true,java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        fileHelpers.stopped(command,runner.id(),never);
        assertEquals(never,fileHelpers.retirement(command));
        assertEquals("FILE_HELPER_STOPPED",assertThrows(WorkspaceFailure.class,()->fileHelpers.created(command,runner.id(),
                new FileHelperData.Physical("d".repeat(64),intent.imageId(),null,null))).code());
    }
    private EnvironmentUseData.Command use() throws Exception {
        var ready=readyEnvironment();return uses.admit(alice,ready.workspaceId(),UUID.randomUUID(),UUID.randomUUID(),probe()).command();
    }
    private EnvironmentUseData.Fact known(EnvironmentUseData.Command command) {
        return new EnvironmentUseData.Fact(command.id(),command.environmentId(),command.generation(),command.fence(),command.requestHash(),runner.id(),"KNOWN",
                new SandboxRuntime.Output("1".repeat(64),0,"666\n","",false,false,false,null),null,null);
    }
    private EnvironmentUseData.Fact stopped(EnvironmentUseData.Command command,UUID runnerId) {
        var now=java.time.Instant.now();
        var stop=new SandboxRuntime.Stop(command.containerId(),command.imageId(),command.containerStartedAt(),now.minusSeconds(1).toString(),true,true,now);
        return new EnvironmentUseData.Fact(command.id(),command.environmentId(),command.generation(),command.fence(),command.requestHash(),runnerId,"UNKNOWN",null,stop,"ENVIRONMENT_USE_INTERRUPTED");
    }
    private void startUse(EnvironmentUseData.Command command) {
        assertTrue(uses.accept(command,runner.id()));assertFalse(uses.accept(command,runner.id()));
        uses.recordExec(command,runner.id(),command.containerId(),"1".repeat(64));
    }
    @Test void privateUseIdempotencyNeverTransfersOwnerAndRejectsConcurrentWriters() throws Exception {
        var ready=readyEnvironment();UUID request=UUID.randomUUID(),owner=UUID.randomUUID();
        var first=uses.admit(alice,ready.workspaceId(),request,owner,probe());assertTrue(first.fresh());
        var duplicate=uses.admit(alice,ready.workspaceId(),request,UUID.randomUUID(),probe());
        assertFalse(duplicate.fresh());assertEquals(first.command(),duplicate.command());assertEquals(owner,duplicate.command().ownerId());
        assertEquals(2,first.command().fence());assertEquals(1,first.command().creationFence());
        assertEquals("ENVIRONMENT_USE_BUSY",assertThrows(WorkspaceFailure.class,()->uses.admit(alice,ready.workspaceId(),UUID.randomUUID(),UUID.randomUUID(),probe())).code());
        var changed=new EnvironmentUseData.Request(null,SandboxRuntime.Probe.NODE_CROSS_READ);
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(WorkspaceFailure.class,()->uses.admit(alice,ready.workspaceId(),request,owner,changed)).code());
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_environment_use"));
    }
    @Test void privateUseRollbackRestoresFenceAndAdmissionRecords() throws Exception {
        var ready=readyEnvironment();
        assertThrows(IllegalStateException.class,()->transactions.execute(status->{
            uses.admit(alice,ready.workspaceId(),UUID.randomUUID(),UUID.randomUUID(),probe());throw new IllegalStateException("Injected use rollback");
        }));
        assertEquals(0,count("SELECT count(*) FROM pf.workspace_environment_use"));assertEquals(1,count("SELECT fence FROM pf.workspace_environment"));
        assertEquals(2,count("SELECT count(*) FROM pf.workspace_environment_event"));
    }
    @Test void privateUseRejectsForgedActorPathsAndUnboundedContent() throws Exception {
        var ready=readyEnvironment();
        assertEquals(404,assertThrows(WorkspaceFailure.class,()->uses.admit(bob,ready.workspaceId(),UUID.randomUUID(),UUID.randomUUID(),probe())).status());
        for(String path:List.of("/etc/passwd","../other","a/../b","a//b","a\\b")) {
            var request=new EnvironmentUseData.Request(new SandboxRuntime.FileRequest(SandboxRuntime.FileAction.READ,path,null),null);
            assertThrows(WorkspaceFailure.class,()->uses.admit(alice,ready.workspaceId(),UUID.randomUUID(),UUID.randomUUID(),request));
        }
        var request=new EnvironmentUseData.Request(new SandboxRuntime.FileRequest(SandboxRuntime.FileAction.WRITE,"large",Base64.getEncoder().encodeToString(new byte[32769])),null);
        assertThrows(WorkspaceFailure.class,()->uses.admit(alice,ready.workspaceId(),UUID.randomUUID(),UUID.randomUUID(),request));
        assertEquals(0,count("SELECT count(*) FROM pf.workspace_environment_use"));
    }
    @Test void committedPrivateOutputConvergesAfterOwnerExpiryWithoutNewExecution() throws Exception {
        var command=use();startUse(command);uses.commitFact(command,runner.id(),known(command));
        // Immutable result commits first. A later cancellation cannot replace that fact.
        uses.cancel(alice,command.id());assertEquals(0,count("SELECT count(*) FROM pf.workspace_environment_use WHERE cancel_requested"));
        sql("UPDATE pf.workspace_environment_use SET lease_until=clock_timestamp()-interval '1 second'");
        assertThrows(WorkspaceFailure.class,()->uses.check(command,runner.id()));
        uses.complete(command);uses.complete(command);
        assertEquals("COMMITTED",uses.receipt(command).state());assertEquals("666\n",uses.result(alice,command.id()).output().stdout());
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_environment_use_result"));
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_sandbox_slot WHERE environment_id IS NOT NULL"));
        assertEquals(404,assertThrows(WorkspaceFailure.class,()->uses.result(bob,command.id())).status());
        assertEquals(command,uses.lookup(alice,command.id()));
        assertEquals(404,assertThrows(WorkspaceFailure.class,()->uses.lookup(bob,command.id())).status());
    }
    @Test void idleStopUsesCurrentFenceAfterSuccessfulPrivateUse() throws Exception {
        var command=use();startUse(command);uses.commitFact(command,runner.id(),known(command));uses.complete(command);
        sql("UPDATE pf.workspace_environment SET last_activity_at=clock_timestamp()-interval '61 seconds'");
        assertEquals(1,maintenance.scheduleStop());assertEquals(3,count("SELECT fence FROM pf.workspace_environment"));
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_environment_outbox o JOIN pf.workspace_environment_operation p ON p.id=o.operation_id WHERE p.kind='STOP'"));
    }
    @Test void unresolvedPrivateUseRetainsSlotAndStopsIdleCleanup() throws Exception {
        var command=use();startUse(command);uses.uncertain(command,"RUNNER_UNAVAILABLE");
        sql("UPDATE pf.workspace_environment_use SET lease_until=clock_timestamp()-interval '1 second'");
        sql("UPDATE pf.workspace_environment SET last_activity_at=clock_timestamp()-interval '61 seconds'");
        assertEquals(0,maintenance.scheduleStop());assertEquals("UNKNOWN",uses.receipt(command).resultState());
        assertEquals("BLOCKED",environments.get(alice,command.workspaceId()).state());
        assertThrows(WorkspaceFailure.class,()->uses.admit(alice,command.workspaceId(),UUID.randomUUID(),UUID.randomUUID(),probe()));
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_sandbox_slot WHERE environment_id IS NOT NULL"));
    }
    @Test void cancelledOrRevokedUseRejectsSuccessButAcceptsExactStopFact() throws Exception {
        var command=use();startUse(command);uses.cancel(alice,command.id());
        assertThrows(WorkspaceFailure.class,()->uses.commitFact(command,runner.id(),known(command)));
        sql("UPDATE pf.membership SET status='REVOKED' WHERE user_id='"+alice.userId()+"'");
        sql("UPDATE pf.workspace_environment_use SET lease_until=clock_timestamp()-interval '1 second'");
        uses.commitFact(command,runner.id(),stopped(command,runner.id()));uses.complete(command);
        assertTrue(uses.stopCommitted(command.id()));assertEquals("STOPPED",uses.receipt(command).state());
        assertEquals("UNKNOWN",uses.receipt(command).resultState());
        assertEquals(0,count("SELECT count(*) FROM pf.workspace_sandbox_slot WHERE environment_id IS NOT NULL"));
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_environment_operation WHERE effect_origin='USE_TERMINATION' AND command_id IS NULL AND source_use_id='"+command.id()+"'"));
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_environment_outbox")); // Only original PREPARE, no fabricated delivery.
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_environment_stop_receipt WHERE source_use_id='"+command.id()+"'"));
        assertEquals(1,count("SELECT reserved_slots FROM pf.workspace_capacity")); // Storage remains reserved.
    }
    @Test void commitTimeLeaseExpiryRollsBackExecBinding() throws Exception {
        var command=use();assertTrue(uses.accept(command,runner.id()));
        assertThrows(WorkspaceFailure.class,()->transactions.execute(status->{
            uses.recordExec(command,runner.id(),command.containerId(),"1".repeat(64));
            try {sqlInTransaction("UPDATE pf.workspace_environment_use SET lease_until=clock_timestamp()-interval '1 second'");}
            catch(Exception failure) {throw new RuntimeException(failure);}return null;
        }));
        assertEquals(0,count("SELECT count(*) FROM pf.workspace_environment_use WHERE exec_id IS NOT NULL"));
    }
    private void sqlInTransaction(String text) throws Exception {
        var connection=org.springframework.jdbc.datasource.DataSourceUtils.getConnection(pool);
        try(var statement=connection.createStatement()) {statement.execute(text);}
        finally {org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(connection,pool);}
    }
    @Test void replacementRunnerRequiresProofOfOriginalBeforeStopReconciliation() throws Exception {
        var command=use();startUse(command);
        var replacement=new RunnerInstance(UUID.randomUUID(),"2".repeat(64),runner.imageId(),runner.startedAt());executions.registerRunner(replacement);
        assertEquals("RUNNER_INSTANCE_STOP_UNPROVEN",assertThrows(WorkspaceFailure.class,()->uses.commitFact(command,replacement.id(),stopped(command,replacement.id()))).code());
        var now=java.time.Instant.now();runnerProofs.record(new StoppedRunner(runner,now.minusSeconds(2),now,hash("private-use-stop-proof")));
        uses.commitFact(command,replacement.id(),stopped(command,replacement.id()));uses.complete(command);
        assertEquals(1,count("SELECT count(*) FROM pf.workspace_environment_use_result WHERE writer_kind='SYSTEM_STOP' AND prior_runner_proof_id='"+runner.id()+"'"));
        assertTrue(uses.stopCommitted(command.id()));
    }
    @Test void lostPostBeforeAcceptanceConsumesAcceptanceAsStopOnlyAndRejectsLateExecution() throws Exception {
        var command=use();uses.checkStopObservation(command,runner.id());
        assertFalse(uses.accept(command,runner.id())); // Query won the acceptance race; late POST must not execute.
        uses.commitFact(command,runner.id(),stopped(command,runner.id()));uses.complete(command);
        assertTrue(uses.stopCommitted(command.id()));assertEquals("UNKNOWN",uses.receipt(command).resultState());
        assertEquals(0,count("SELECT count(*) FROM pf.workspace_environment_use WHERE exec_id IS NOT NULL"));
    }
    @Test void restrictedRunnerCanBindExecAndResultButCannotFinishUseOrChangeLease() throws Exception {
        for(String role:List.of("ap_api","ap_worker","ap_runner","ap_workspace_ops"))sql("DO $$ BEGIN CREATE ROLE "+role+" NOLOGIN; EXCEPTION WHEN duplicate_object THEN NULL; END $$");
        sql(Files.readString(Path.of(System.getenv("R32_WORKSPACE_GRANTS_FILE"))));sql(Files.readString(Path.of(System.getenv("R32_ENVIRONMENT_GRANTS_FILE"))));
        var command=use();
        var configuration=new com.zaxxer.hikari.HikariConfig();configuration.setJdbcUrl(url);configuration.setUsername("postgres");configuration.setPassword(password);
        configuration.setMaximumPoolSize(2);configuration.setMinimumIdle(1);configuration.setConnectionInitSql("SET ROLE ap_runner");
        try(var restricted=new com.zaxxer.hikari.HikariDataSource(configuration)) {
            var session=session(restricted);var transactions=new TransactionTemplate(new JdbcTransactionManager(restricted));
            var port=new TransactionalEnvironmentUseRepository(new MyBatisEnvironmentUseRepository(session.getMapper(EnvironmentUseMapper.class),
                    session.getMapper(EnvironmentPreparationMapper.class),session.getMapper(EnvironmentExecutionMapper.class),session.getMapper(WorkspaceMapper.class)),transactions);
            assertTrue(port.accept(command,runner.id()));port.recordExec(command,runner.id(),command.containerId(),"1".repeat(64));
            port.commitFact(command,runner.id(),known(command));assertEquals("KNOWN",port.receipt(command).resultState());
            for(String update:List.of("status='COMMITTED'","owner_id='"+UUID.randomUUID()+"'","lease_until=clock_timestamp()+interval '1 hour'","fence=fence+1"))
                denied(restricted,"UPDATE pf.workspace_environment_use SET "+update);
            denied(restricted,"UPDATE pf.workspace_environment_use_result SET exit_code=0");
        }
        uses.complete(command);assertEquals("COMMITTED",uses.receipt(command).state());
    }
    private static void denied(javax.sql.DataSource source,String query) throws Exception {
        try(var connection=source.getConnection();var statement=connection.createStatement()) {
            assertEquals("42501",assertThrows(java.sql.SQLException.class,()->statement.execute(query)).getSQLState());
        }
    }
}
