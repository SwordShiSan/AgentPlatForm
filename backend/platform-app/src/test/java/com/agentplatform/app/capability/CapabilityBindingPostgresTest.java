package com.agentplatform.app.capability;

import static org.junit.jupiter.api.Assertions.*;

import com.agentplatform.domain.capability.CapabilityBindingRepository.Actor;
import com.agentplatform.domain.capability.CapabilityBindings.Selection;
import com.agentplatform.domain.chat.ChatFailure;
import com.agentplatform.infrastructure.adapter.repository.capability.MyBatisCapabilityBindingRepository;
import com.agentplatform.infrastructure.dao.capability.CapabilityBindingMapper;
import com.agentplatform.infrastructure.dao.typehandler.UuidTypeHandler;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Exercises actual migration, mapper and versioned transactions on an isolated database. */
class CapabilityBindingPostgresTest {
    static String url, password;
    HikariDataSource pool, apiPool;
    CapabilityBindingService service;
    Actor alice, bob;
    UUID capability, version, workspace;

    static void sql(String statement) throws Exception {
        try (var connection = DriverManager.getConnection(url, "postgres", password);
             var query = connection.createStatement()) { query.execute(statement); }
    }

    @BeforeAll static void migrate() throws Exception {
        url = System.getenv("R62_TEST_JDBC_URL");
        Assumptions.assumeTrue(url != null, "R62 isolated PostgreSQL NOT_RUN");
        int port = Integer.parseInt(System.getenv("R62_TEST_PORT"));
        assertEquals("jdbc:postgresql://127.0.0.1:" + port + "/r62_test?sslmode=disable", url);
        assertEquals("1", System.getenv("R62_ACCEPTANCE"));
        password = Files.readString(Path.of(System.getenv("R62_TEST_PASSWORD_FILE"))).strip();
        var base = Flyway.configure().dataSource(url, "postgres", password).defaultSchema("pf")
                .schemas("pf", "pf_runtime").cleanDisabled(false).load();
        base.clean();
        var old = Flyway.configure().dataSource(url, "postgres", password).defaultSchema("pf")
                .schemas("pf", "pf_runtime").target("22").load();
        old.migrate(); assertEquals("22", old.info().current().getVersion().toString());
        base.migrate(); base.validate(); assertEquals("23", base.info().current().getVersion().toString());
        sql("CREATE ROLE r62_api NOLOGIN; GRANT USAGE ON SCHEMA pf TO r62_api; "
                + "GRANT SELECT ON pf.space,pf.workspace TO r62_api; GRANT UPDATE(version) ON pf.workspace TO r62_api; "
                + "GRANT EXECUTE ON FUNCTION pf.authorize_workspace_actor(uuid,uuid) TO r62_api; "
                + "GRANT SELECT,INSERT,UPDATE ON pf.capability_binding_set TO r62_api; "
                + "GRANT SELECT,INSERT,DELETE ON pf.capability_binding_selection TO r62_api; "
                + "GRANT SELECT,INSERT ON pf.capability_binding_request,pf.capability_binding_request_selection TO r62_api; "
                + "GRANT INSERT ON pf.capability_audit TO r62_api; "
                + "GRANT SELECT ON pf.capability_version_status TO r62_api; GRANT UPDATE(revision) ON pf.capability_version_status TO r62_api");
    }

    @BeforeEach void setup() throws Exception {
        sql("TRUNCATE pf.app_user CASCADE");
        var config = new HikariConfig(); config.setJdbcUrl(url); config.setUsername("postgres");
        config.setPassword(password); config.setMaximumPoolSize(4); config.setMinimumIdle(0);
        pool = new HikariDataSource(config);
        var apiConfig = new HikariConfig(); apiConfig.setJdbcUrl(url); apiConfig.setUsername("postgres");
        apiConfig.setPassword(password); apiConfig.setMaximumPoolSize(4); apiConfig.setMinimumIdle(0);
        apiConfig.setConnectionInitSql("SET ROLE r62_api"); apiPool = new HikariDataSource(apiConfig);
        var identity = com.agentplatform.app.identity.IdentityPersistenceFixture.create(pool);
        UUID first = identity.provision(UUID.randomUUID(), "r62-" + UUID.randomUUID(),
                "r62_" + UUID.randomUUID().toString().replace("-", ""), "fixture-only", true);
        UUID other = identity.provision(UUID.randomUUID(), "r62-" + UUID.randomUUID(),
                "r62_" + UUID.randomUUID().toString().replace("-", ""), "fixture-only", false);
        alice = new Actor(first, identity.spaces(first).getFirst().id());
        bob = new Actor(other, identity.spaces(other).getFirst().id());
        workspace = UUID.randomUUID(); UUID operation = UUID.randomUUID();
        capability = UUID.randomUUID(); version = UUID.randomUUID();
        sql("BEGIN; INSERT INTO pf.workspace(id,space_id,actor_id,name,state,quota_bytes,quota_inodes,project_id,operation_id) VALUES('"
                + workspace + "','" + alice.spaceId() + "','" + first + "','fixture','READY',2147483648,100000,"
                + "(SELECT coalesce(max(project_id),200000)+1 FROM pf.workspace),'" + operation + "');"
                + "INSERT INTO pf.workspace_operation(id,space_id,workspace_id,actor_id,command_id,status) VALUES('"
                + operation + "','" + alice.spaceId() + "','" + workspace + "','" + first + "','" + UUID.randomUUID() + "','COMMITTED');"
                + "INSERT INTO pf.capability(id,space_id,owner_user_id,kind) VALUES('" + capability + "','" + alice.spaceId() + "','" + first + "','SKILL');"
                + "INSERT INTO pf.capability_version(id,space_id,owner_user_id,capability_id,version_number,content_hash) VALUES('"
                + version + "','" + alice.spaceId() + "','" + first + "','" + capability + "',1,repeat('a',64));"
                + "INSERT INTO pf.capability_version_status(space_id,owner_user_id,capability_id,version_id,state) VALUES('"
                + alice.spaceId() + "','" + first + "','" + capability + "','" + version + "','PUBLISHED'); COMMIT");
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(apiPool);
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT); configuration.setCacheEnabled(false);
        configuration.getTypeHandlerRegistry().register(new UuidTypeHandler()); factory.setConfiguration(configuration);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mybatis/mapper/**/*.xml"));
        var mapper = new SqlSessionTemplate(factory.getObject()).getMapper(CapabilityBindingMapper.class);
        service = new CapabilityBindingService(new MyBatisCapabilityBindingRepository(mapper),
                new TransactionTemplate(new JdbcTransactionManager(apiPool)));
    }

    @AfterEach void close() { if (apiPool != null) apiPool.close(); if (pool != null) pool.close(); }

    @Test void workspaceInheritsOrExplicitlyClearsAndStaleRevisionIsRejected() {
        var selected = new Selection(capability, version);
        assertEquals(List.of(selected), service.replace(alice, null, 0, false, List.of(selected)).selections());
        assertEquals(List.of(selected), service.effective(alice, workspace).selections());
        var empty = service.replace(alice, workspace, 0, false, List.of());
        assertFalse(empty.inheritPersonal()); assertTrue(empty.selections().isEmpty());
        assertTrue(service.effective(alice, workspace).selections().isEmpty());
        var inherited = service.replace(alice, workspace, empty.revision(), true, List.of());
        assertTrue(inherited.inheritPersonal());
        assertEquals(List.of(selected), service.effective(alice, workspace).selections());
        assertEquals(412, assertThrows(ChatFailure.class,
                () -> service.replace(alice, workspace, empty.revision(), false, List.of(selected))).status());
        assertTrue(service.read(alice, workspace).inheritPersonal());
    }

    @Test void revokedVersionRemainsSelectedButCannotBeNewlyBound() throws Exception {
        var selected = new Selection(capability, version);
        service.replace(alice, null, 0, false, List.of(selected));
        sql("UPDATE pf.capability_version_status SET state='REVOKED',revision=revision+1 WHERE version_id='" + version + "'");
        assertEquals(List.of(selected), service.effective(alice, workspace).unavailable());
        assertEquals(422, assertThrows(ChatFailure.class,
                () -> service.replace(alice, workspace, 0, false, List.of(selected))).status());
    }

    @Test void otherUserCannotBindVersionOrReadWorkspace() {
        assertEquals(422, assertThrows(ChatFailure.class,
                () -> service.replace(bob, null, 0, false, List.of(new Selection(capability, version)))).status());
        assertEquals(404, assertThrows(ChatFailure.class, () -> service.read(bob, workspace)).status());
    }

    @Test void lostResponseReturnsOriginalResultAndChangedRequestIsRejected() {
        var selected = new Selection(capability, version);
        var request = UUID.randomUUID(); var trace = UUID.randomUUID();
        var first = service.replace(alice, null, 0, false, List.of(selected), request, trace);
        service.replace(alice, null, first.revision(), false, List.of());
        var replay = service.replace(alice, null, 0, false, List.of(selected), request, trace);
        assertEquals(first, replay);
        assertEquals(409, assertThrows(ChatFailure.class,
                () -> service.replace(alice, null, 0, false, List.of(), request, trace)).status());
        assertTrue(service.read(alice, null).selections().isEmpty());
    }
}
