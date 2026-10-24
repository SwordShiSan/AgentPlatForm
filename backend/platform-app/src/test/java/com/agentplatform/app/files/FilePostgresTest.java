package com.agentplatform.app.files;

import com.agentplatform.app.identity.IdentityPersistenceFixture;
import com.agentplatform.app.workspace.TransactionalWorkspaceRepository;
import com.agentplatform.domain.files.*;
import com.agentplatform.domain.files.FileData.*;
import com.agentplatform.domain.workspace.WorkspaceRepository;
import com.agentplatform.infrastructure.adapter.repository.files.MyBatisFileRepository;
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
        var workspaceMapper=session.getMapper(WorkspaceMapper.class);
        workspaces=new TransactionalWorkspaceRepository(new MyBatisWorkspaceRepository(workspaceMapper),transactions);
        files=new TransactionalFileRepository(new MyBatisFileRepository(session.getMapper(FileMapper.class),workspaceMapper,
                session.getMapper(EnvironmentPreparationMapper.class)),transactions);
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
}
