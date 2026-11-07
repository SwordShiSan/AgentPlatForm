package com.agentplatform.app.capability;

import static org.junit.jupiter.api.Assertions.*;

import com.agentplatform.domain.capability.CapabilityBindingRepository.Actor;
import com.agentplatform.domain.capability.CapabilityBindings.Selection;
import com.agentplatform.domain.chat.ChatFailure;
import com.agentplatform.infrastructure.adapter.repository.capability.MyBatisCapabilityBindingRepository;
import com.agentplatform.infrastructure.dao.capability.CapabilityBindingMapper;
import com.agentplatform.infrastructure.dao.capability.SkillImportMapper;
import com.agentplatform.infrastructure.dao.capability.SkillWorkerMapper;
import com.agentplatform.infrastructure.dao.capability.SkillVersionMapper;
import com.agentplatform.infrastructure.dao.capability.SkillCatalogMapper;
import com.agentplatform.infrastructure.adapter.repository.capability.MyBatisSkillVersionRepository;
import com.agentplatform.infrastructure.adapter.repository.capability.MyBatisSkillCatalogRepository;
import com.agentplatform.infrastructure.dao.capability.ConnectorCredentialMapper;
import com.agentplatform.infrastructure.adapter.repository.capability.MyBatisConnectorCredentialRepository;
import com.agentplatform.infrastructure.dao.capability.McpConnectorMapper;
import com.agentplatform.infrastructure.dao.capability.McpProbeMapper;
import com.agentplatform.infrastructure.dao.capability.McpVersionMapper;
import com.agentplatform.infrastructure.adapter.repository.capability.MyBatisMcpVersionRepository;
import com.agentplatform.infrastructure.adapter.repository.capability.MyBatisMcpConnectorRepository;
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
    HikariDataSource pool, apiPool, workerPool;
    CapabilityBindingService service;
    TransactionalSkillImportRepository imports;
    TransactionalSkillValidationRepository worker;
    SkillVersionService versions;
    SkillCatalogService catalog;
    ConnectorCredentialService connectorCredentials;
    McpConnectorService connectors;
    McpVersionService mcpVersions;
    TransactionalMcpProbeRepository mcpWorker;
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
                + "GRANT SELECT ON pf.space_storage TO r62_api; GRANT UPDATE(reserved_bytes,committed_bytes,version) ON pf.space_storage TO r62_api; GRANT SELECT,INSERT ON pf.capability TO r62_api; GRANT UPDATE(kind) ON pf.capability TO r62_api; "
                + "GRANT SELECT,INSERT,UPDATE ON pf.skill_import,pf.skill_import_reservation,pf.skill_import_outbox TO r62_api; "
                + "GRANT SELECT,INSERT ON pf.skill_import_completion_request TO r62_api; "
                + "GRANT SELECT ON pf.capability_version,pf.skill_package_version,pf.asset_object TO r62_api; "
                + "GRANT SELECT,INSERT ON pf.capability_version_command TO r62_api; "
                + "GRANT SELECT(id,space_id,owner_user_id,request_id,request_fingerprint,revision,state,created_at) ON pf.connector_credential TO r62_api; "
                + "GRANT INSERT(id,space_id,owner_user_id,request_id,request_fingerprint,ciphertext,nonce,key_version,state) ON pf.connector_credential TO r62_api; "
                + "GRANT UPDATE(state,revision) ON pf.connector_credential TO r62_api; GRANT SELECT,INSERT ON pf.connector_credential_revocation TO r62_api; "
                + "GRANT SELECT,INSERT,UPDATE ON pf.mcp_connector_configuration TO r62_api; GRANT SELECT,INSERT ON pf.mcp_connector_request,pf.mcp_probe,pf.mcp_probe_outbox TO r62_api; "
                + "GRANT SELECT,INSERT,UPDATE ON pf.mcp_connector_activation TO r62_api; GRANT SELECT,INSERT ON pf.mcp_activation_command TO r62_api; GRANT SELECT ON pf.mcp_connector_version,pf.mcp_connector_tool TO r62_api; "
                + "GRANT UPDATE(state,updated_at) ON pf.capability_version_status TO r62_api; "
                + "GRANT SELECT ON pf.capability_version_status TO r62_api; GRANT UPDATE(revision) ON pf.capability_version_status TO r62_api; "
                + "CREATE ROLE r62_worker NOLOGIN; GRANT USAGE ON SCHEMA pf TO r62_worker; "
                + "GRANT EXECUTE ON FUNCTION pf.authorize_workspace_actor(uuid,uuid) TO r62_worker; "
                + "GRANT SELECT ON pf.space_storage TO r62_worker; GRANT UPDATE(reserved_bytes,committed_bytes,version) ON pf.space_storage TO r62_worker; "
                + "GRANT SELECT,INSERT,UPDATE ON pf.skill_import,pf.skill_import_reservation,pf.skill_import_outbox,pf.skill_import_inbox TO r62_worker; "
                + "GRANT SELECT,INSERT ON pf.capability TO r62_worker; GRANT UPDATE(kind) ON pf.capability TO r62_worker; GRANT SELECT,INSERT ON pf.capability_version,pf.skill_package_version,pf.capability_version_status,pf.asset_object TO r62_worker; "
                + "GRANT SELECT,UPDATE ON pf.mcp_probe,pf.mcp_probe_outbox TO r62_worker; GRANT SELECT,INSERT ON pf.mcp_probe_inbox TO r62_worker; "
                + "GRANT SELECT ON pf.mcp_connector_configuration TO r62_worker; GRANT UPDATE(revision) ON pf.mcp_connector_configuration TO r62_worker; "
                + "GRANT SELECT(id,space_id,owner_user_id,ciphertext,nonce,key_version,revision,state) ON pf.connector_credential TO r62_worker; GRANT UPDATE(revision) ON pf.connector_credential TO r62_worker; "
                + "GRANT SELECT,INSERT ON pf.mcp_connector_version,pf.mcp_connector_tool TO r62_worker; GRANT INSERT ON pf.mcp_probe_quarantine TO r62_worker; "
                + "GRANT INSERT ON pf.capability_audit,pf.skill_import_quarantine TO r62_worker");
    }

    @BeforeEach void setup() throws Exception {
        sql("TRUNCATE pf.app_user CASCADE");
        var config = new HikariConfig(); config.setJdbcUrl(url); config.setUsername("postgres");
        config.setPassword(password); config.setMaximumPoolSize(4); config.setMinimumIdle(0);
        pool = new HikariDataSource(config);
        var apiConfig = new HikariConfig(); apiConfig.setJdbcUrl(url); apiConfig.setUsername("postgres");
        apiConfig.setPassword(password); apiConfig.setMaximumPoolSize(4); apiConfig.setMinimumIdle(0);
        apiConfig.setConnectionInitSql("SET ROLE r62_api"); apiPool = new HikariDataSource(apiConfig);
        var workerConfig = new HikariConfig(); workerConfig.setJdbcUrl(url); workerConfig.setUsername("postgres");
        workerConfig.setPassword(password); workerConfig.setMaximumPoolSize(4); workerConfig.setMinimumIdle(0);
        workerConfig.setConnectionInitSql("SET ROLE r62_worker"); workerPool = new HikariDataSource(workerConfig);
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
        var session = new SqlSessionTemplate(factory.getObject());
        var mapper = session.getMapper(CapabilityBindingMapper.class);
        service = new CapabilityBindingService(new MyBatisCapabilityBindingRepository(mapper),
                new TransactionTemplate(new JdbcTransactionManager(apiPool)));
        imports = new TransactionalSkillImportRepository(session.getMapper(SkillImportMapper.class),
                new TransactionTemplate(new JdbcTransactionManager(apiPool)));
        versions = new SkillVersionService(new MyBatisSkillVersionRepository(session.getMapper(SkillVersionMapper.class)),
                new TransactionTemplate(new JdbcTransactionManager(apiPool)));
        catalog = new SkillCatalogService(new MyBatisSkillCatalogRepository(session.getMapper(SkillCatalogMapper.class)),
                new TransactionTemplate(new JdbcTransactionManager(apiPool)));
        byte[] mcpKey = new byte[32]; java.util.Arrays.fill(mcpKey, (byte) 7);
        connectorCredentials = new ConnectorCredentialService(new MyBatisConnectorCredentialRepository(session.getMapper(ConnectorCredentialMapper.class)),
                new TransactionTemplate(new JdbcTransactionManager(apiPool)), mcpKey);
        java.util.Arrays.fill(mcpKey, (byte) 0);
        connectors = new McpConnectorService(new MyBatisMcpConnectorRepository(session.getMapper(McpConnectorMapper.class)),
                new TransactionTemplate(new JdbcTransactionManager(apiPool)), "a".repeat(64));
        mcpVersions = new McpVersionService(new MyBatisMcpVersionRepository(session.getMapper(McpVersionMapper.class)),
                new TransactionTemplate(new JdbcTransactionManager(apiPool)), "a".repeat(64));
        var workerFactory = new SqlSessionFactoryBean(); workerFactory.setDataSource(workerPool);
        var workerConfiguration = new org.apache.ibatis.session.Configuration(); workerConfiguration.setMapUnderscoreToCamelCase(true);
        workerConfiguration.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT); workerConfiguration.setCacheEnabled(false);
        workerConfiguration.getTypeHandlerRegistry().register(new UuidTypeHandler()); workerFactory.setConfiguration(workerConfiguration);
        workerFactory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mybatis/mapper/**/*.xml"));
        var workerSession = new SqlSessionTemplate(workerFactory.getObject());
        worker = new TransactionalSkillValidationRepository(workerSession.getMapper(SkillWorkerMapper.class),
                new TransactionTemplate(new JdbcTransactionManager(workerPool)));
        mcpWorker = new TransactionalMcpProbeRepository(workerSession.getMapper(McpProbeMapper.class),
                new TransactionTemplate(new JdbcTransactionManager(workerPool)), "a".repeat(64));
    }

    @AfterEach void close() { if (workerPool != null) workerPool.close(); if (apiPool != null) apiPool.close(); if (pool != null) pool.close(); }

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
        assertThrows(java.sql.SQLException.class, () -> sql("UPDATE pf.capability_version_status SET state='PUBLISHED',revision=revision+1 WHERE version_id='" + version + "'"));
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

    @Test void importReservesOnceAndQueuesOnceWithAuthorizedOwner() throws Exception {
        var actor = new com.agentplatform.domain.capability.SkillImportRepository.Actor(alice.userId(), alice.spaceId());
        var other = new com.agentplatform.domain.capability.SkillImportRepository.Actor(bob.userId(), bob.spaceId());
        UUID request = UUID.randomUUID(); String hash = "a".repeat(64), sha = "b".repeat(64);
        var first = imports.create(actor, request, hash, null, 123, sha);
        assertEquals(first.id(), imports.create(actor, request, hash, null, 123, sha).id());
        assertEquals(first.id(), imports.findRequest(actor, request).id());
        assertEquals(404, assertThrows(com.agentplatform.domain.files.FileFailure.class,
                () -> imports.findRequest(other, request)).status());
        assertEquals(409, assertThrows(com.agentplatform.domain.files.FileFailure.class,
                () -> imports.create(actor, request, "c".repeat(64), null, 123, sha)).status());
        assertEquals(404, assertThrows(com.agentplatform.domain.files.FileFailure.class,
                () -> imports.find(other, first.id())).status());
        UUID owner = UUID.randomUUID(); imports.beginTransfer(actor, first.id(), owner);
        imports.guardTransfer(actor, first.id(), owner); imports.verified(actor, first.id(), owner);
        UUID completion = UUID.randomUUID();
        assertEquals("QUEUED", imports.enqueue(actor, first.id(), completion).state());
        assertEquals("QUEUED", imports.enqueue(actor, first.id(), completion).state());
        var another = imports.create(actor, UUID.randomUUID(), "d".repeat(64), null, 0, sha);
        assertEquals(409, assertThrows(com.agentplatform.domain.files.FileFailure.class,
                () -> imports.enqueue(actor, another.id(), completion)).status());
        try (var connection = DriverManager.getConnection(url, "postgres", password);
             var query = connection.createStatement();
             var rows = query.executeQuery("SELECT reserved_bytes,(SELECT count(*) FROM pf.skill_import_outbox WHERE import_id='"
                     + first.id() + "') FROM pf.space_storage WHERE space_id='" + alice.spaceId() + "'")) {
            assertTrue(rows.next()); assertEquals(123, rows.getLong(1)); assertEquals(1, rows.getInt(2));
        }
    }

    @Test void uncertainImportRetainsQuotaForReconciliation() throws Exception {
        var actor = new com.agentplatform.domain.capability.SkillImportRepository.Actor(alice.userId(), alice.spaceId());
        var row = imports.create(actor, UUID.randomUUID(), "a".repeat(64), null, 123, "b".repeat(64));
        UUID owner = UUID.randomUUID(); imports.beginTransfer(actor, row.id(), owner);
        imports.transferUnknown(actor, row.id(), owner);
        assertEquals("BLOCKED", imports.find(actor, row.id()).state());
        try (var connection = DriverManager.getConnection(url, "postgres", password);
             var query = connection.createStatement();
             var rows = query.executeQuery("SELECT s.reserved_bytes,r.state FROM pf.space_storage s JOIN pf.skill_import_reservation r ON r.space_id=s.space_id WHERE r.import_id='" + row.id() + "'")) {
            assertTrue(rows.next()); assertEquals(123, rows.getLong(1)); assertEquals("RECONCILING", rows.getString(2));
        }
    }

    @Test void revokedMemberStopsQueuedSkillValidationAndRetainsReservation() throws Exception {
        var actor = new com.agentplatform.domain.capability.SkillImportRepository.Actor(alice.userId(), alice.spaceId());
        var row = imports.create(actor, UUID.randomUUID(), "a".repeat(64), null, 123, "b".repeat(64));
        UUID owner = UUID.randomUUID(); imports.beginTransfer(actor, row.id(), owner); imports.verified(actor, row.id(), owner);
        imports.enqueue(actor, row.id(), UUID.randomUUID());
        sql("UPDATE pf.membership SET status='REVOKED' WHERE space_id='" + alice.spaceId() + "' AND user_id='" + alice.userId() + "'");
        var publication = worker.nextPublication(UUID.randomUUID()); worker.published(publication);
        assertNull(worker.claim(publication, UUID.randomUUID()));
        try (var connection = DriverManager.getConnection(url, "postgres", password); var query = connection.createStatement();
             var rows = query.executeQuery("SELECT i.state,r.state FROM pf.skill_import i JOIN pf.skill_import_reservation r ON r.import_id=i.id WHERE i.id='" + row.id() + "'")) {
            assertTrue(rows.next()); assertEquals("BLOCKED", rows.getString(1)); assertEquals("RECONCILING", rows.getString(2));
        }
    }

    @Test void concurrentImportsCannotOversellQuota() throws Exception {
        sql("UPDATE pf.space_storage SET object_limit_bytes=100 WHERE space_id='" + alice.spaceId() + "'");
        var actor = new com.agentplatform.domain.capability.SkillImportRepository.Actor(alice.userId(), alice.spaceId());
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var attempts = java.util.stream.IntStream.range(0, 2).mapToObj(i -> executor.submit(() -> {
                start.await();
                try { imports.create(actor, UUID.randomUUID(), "a".repeat(64), null, 70, "b".repeat(64)); return 200; }
                catch (com.agentplatform.domain.files.FileFailure failure) { return failure.status(); }
            })).toList();
            start.countDown();
            var results = new java.util.ArrayList<Integer>();
            for (var attempt : attempts) results.add(attempt.get(30, java.util.concurrent.TimeUnit.SECONDS));
            java.util.Collections.sort(results);
            assertEquals(List.of(200, 422), results);
        }
    }

    @Test void validationCommitsImmutableDraftAndSettlesQuotaOnce() throws Exception {
        var actor = new com.agentplatform.domain.capability.SkillImportRepository.Actor(alice.userId(), alice.spaceId());
        String hash = "b".repeat(64); var row = imports.create(actor, UUID.randomUUID(), "a".repeat(64), null, 123, hash);
        UUID transfer = UUID.randomUUID(); imports.beginTransfer(actor, row.id(), transfer); imports.verified(actor, row.id(), transfer);
        imports.enqueue(actor, row.id(), UUID.randomUUID());
        var publication = worker.nextPublication(UUID.randomUUID()); assertNotNull(publication); worker.published(publication);
        var command = worker.claim(publication, UUID.randomUUID()); assertNotNull(command);
        assertNull(worker.claim(publication, UUID.randomUUID()));
        worker.guard(command);
        var stale = new com.agentplatform.domain.capability.SkillValidationRepository.Command(command.spaceId(), command.actorId(),
                command.importId(), command.ownerId(), command.generation(), command.fence()+1,
                command.objectKey(), command.bytes(), command.sha256());
        assertThrows(com.agentplatform.domain.files.FileFailure.class, () -> worker.guard(stale));
        var manifest = new com.agentplatform.domain.capability.SkillValidationRepository.Manifest(hash, "sample", "description", 1,
                List.of(new com.agentplatform.domain.capability.SkillValidationRepository.Resource("SKILL.md", 1, "c".repeat(64))), "[]");
        worker.draft(command, manifest);
        assertEquals("DRAFT", imports.find(actor, row.id()).state());
        var caller = new com.agentplatform.api.chat.ChatApi.Caller(alice.userId().toString(), alice.spaceId().toString());
        assertEquals(row.versionId().toString(), catalog.skills(caller, null, 10).items().getFirst().versionId());
        assertEquals(1, catalog.versions(caller, row.capabilityId().toString(), null, 10).items().size());
        var foreign = new com.agentplatform.api.chat.ChatApi.Caller(bob.userId().toString(), bob.spaceId().toString());
        assertTrue(catalog.skills(foreign, null, 10).items().isEmpty());
        assertEquals(404, assertThrows(com.agentplatform.domain.files.FileFailure.class,
                () -> catalog.versions(foreign, row.capabilityId().toString(), null, 10)).status());
        assertThrows(com.agentplatform.domain.files.FileFailure.class, () -> worker.draft(command, manifest));
        var request = UUID.randomUUID(); var trace = UUID.randomUUID();
        var published = versions.change(alice, row.capabilityId(), row.versionId(), "PUBLISH", 1, request, trace);
        assertEquals("PUBLISHED", published.state());
        assertEquals(published.revision(), versions.change(alice, row.capabilityId(), row.versionId(), "PUBLISH", 1, request, trace).revision());
        assertEquals("REVOKED", versions.change(alice, row.capabilityId(), row.versionId(), "REVOKE", 2, UUID.randomUUID(), trace).state());
        assertEquals("PUBLISHED", versions.change(alice, row.capabilityId(), row.versionId(), "PUBLISH", 1, request, trace).state());
        assertEquals("REVOKED", versions.get(alice, row.capabilityId(), row.versionId()).state());
        try (var connection = DriverManager.getConnection(url, "postgres", password); var query = connection.createStatement();
             var rows = query.executeQuery("SELECT committed_bytes,reserved_bytes,(SELECT count(*) FROM pf.skill_package_version WHERE version_id='"
                     + row.versionId() + "') FROM pf.space_storage WHERE space_id='" + alice.spaceId() + "'")) {
            assertTrue(rows.next()); assertEquals(123, rows.getLong(1)); assertEquals(0, rows.getLong(2)); assertEquals(1, rows.getInt(3));
        }
    }

    @Test void unresolvedSkillDependenciesCannotBePublished() {
        var actor = new com.agentplatform.domain.capability.SkillImportRepository.Actor(alice.userId(), alice.spaceId());
        String hash = "b".repeat(64); var row = imports.create(actor, UUID.randomUUID(), "a".repeat(64), null, 7, hash);
        UUID transfer = UUID.randomUUID(); imports.beginTransfer(actor, row.id(), transfer); imports.verified(actor, row.id(), transfer);
        imports.enqueue(actor, row.id(), UUID.randomUUID());
        var publication = worker.nextPublication(UUID.randomUUID()); worker.published(publication);
        var command = worker.claim(publication, UUID.randomUUID());
        worker.draft(command, new com.agentplatform.domain.capability.SkillValidationRepository.Manifest(hash, "sample", "description", 1,
                List.of(new com.agentplatform.domain.capability.SkillValidationRepository.Resource("SKILL.md", 1, "c".repeat(64))), "[\"python3\"]"));
        assertEquals(422, assertThrows(com.agentplatform.domain.files.FileFailure.class,
                () -> versions.change(alice, row.capabilityId(), row.versionId(), "PUBLISH", 1, UUID.randomUUID(), UUID.randomUUID())).status());
        assertEquals("DRAFT", versions.get(alice, row.capabilityId(), row.versionId()).state());
    }

    @Test void connectorCredentialsAreEncryptedScopedAndImmediatelyRevoked() throws Exception {
        UUID request = UUID.randomUUID(), trace = UUID.randomUUID(); String fixture = "mcp-fixture-sentinel-secret";
        var first = connectorCredentials.create(alice, request, fixture, trace);
        assertEquals(first.id(), connectorCredentials.create(alice, request, fixture, trace).id());
        assertEquals(409, assertThrows(com.agentplatform.domain.files.FileFailure.class,
                () -> connectorCredentials.create(alice, request, "different-fixture", trace)).status());
        assertTrue(connectorCredentials.list(bob, null, 50).items().isEmpty());
        assertThrows(java.sql.SQLException.class, () -> {
            try (var connection = apiPool.getConnection(); var query = connection.createStatement()) {
                query.executeQuery("SELECT ciphertext FROM pf.connector_credential");
            }
        });
        try (var connection = DriverManager.getConnection(url, "postgres", password); var query = connection.createStatement();
             var rows = query.executeQuery("SELECT position(convert_to('mcp-fixture-sentinel-secret','UTF8') in ciphertext) FROM pf.connector_credential WHERE id='" + first.id() + "'")) {
            assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
        }
        UUID revokeRequest = UUID.randomUUID();
        var revoked = connectorCredentials.revoke(alice, first.id(), first.revision(), revokeRequest, trace);
        assertEquals("REVOKED", revoked.state());
        assertEquals(revoked, connectorCredentials.byRequest(alice, revokeRequest));
        assertEquals(404, assertThrows(com.agentplatform.domain.files.FileFailure.class,
                () -> connectorCredentials.byRequest(bob, revokeRequest)).status());
        assertEquals(404, assertThrows(com.agentplatform.domain.files.FileFailure.class,
                () -> connectorCredentials.revoke(bob, first.id(), first.revision(), UUID.randomUUID(), trace)).status());
    }

    @Test void connectorCredentialReplacementRevokesOldIdentityAtomically() {
        UUID trace = UUID.randomUUID(), request = UUID.randomUUID();
        var old = connectorCredentials.create(alice, UUID.randomUUID(), "fixture-old", trace);
        var next = connectorCredentials.replace(alice, old.id(), old.revision(), request, "fixture-new", trace);
        assertNotEquals(old.id(), next.id());
        assertEquals(next.id(), connectorCredentials.replace(alice, old.id(), old.revision(), request, "fixture-new", trace).id());
        assertEquals(409, assertThrows(com.agentplatform.domain.files.FileFailure.class,
                () -> connectorCredentials.replace(alice, old.id(), old.revision(), request, "changed", trace)).status());
        var rows = connectorCredentials.list(alice, null, 50).items();
        assertEquals("REVOKED", rows.stream().filter(c -> c.id().equals(old.id())).findFirst().orElseThrow().state());
        assertEquals("ACTIVE", rows.stream().filter(c -> c.id().equals(next.id())).findFirst().orElseThrow().state());
    }

    @Test void rejectedSkillImportExposesOnlyStableErrorCode() {
        var actor = new com.agentplatform.domain.capability.SkillImportRepository.Actor(alice.userId(), alice.spaceId());
        var upload = imports.create(actor, UUID.randomUUID(), "a".repeat(64), null, 7, "b".repeat(64));
        UUID transfer = UUID.randomUUID(); imports.beginTransfer(actor, upload.id(), transfer); imports.verified(actor, upload.id(), transfer);
        imports.enqueue(actor, upload.id(), UUID.randomUUID());
        var publication = worker.nextPublication(UUID.randomUUID()); worker.published(publication);
        var command = worker.claim(publication, UUID.randomUUID());
        worker.rejected(command, "SKILL_ARCHIVE_INVALID");
        assertEquals("REJECTED", imports.find(actor, upload.id()).state());
        assertEquals("SKILL_ARCHIVE_INVALID", imports.find(actor, upload.id()).errorCode());
    }

    @Test void probeCompletionCreatesDraftOnceAndRejectsOldFence() throws Exception {
        UUID trace = UUID.randomUUID();
        UUID createRequest = UUID.randomUUID(); UUID probeRequest = UUID.randomUUID();
        var config = connectors.create(alice, createRequest, new McpConnectorService.Input("https://example.com/mcp", "NONE", null, null, null), trace);
        var probe = connectors.enqueue(alice, config.id(), config.revision(), probeRequest, trace);
        assertEquals(config, connectors.byRequest(alice, createRequest));
        assertEquals(probe.id(), connectors.probeByRequest(alice, probeRequest).id());
        assertEquals(404, assertThrows(com.agentplatform.domain.files.FileFailure.class, () -> connectors.byRequest(bob, createRequest)).status());
        assertEquals(404, assertThrows(com.agentplatform.domain.files.FileFailure.class, () -> connectors.probeByRequest(bob, probeRequest)).status());
        var publication = mcpWorker.nextPublication(UUID.randomUUID()); mcpWorker.published(publication);
        var command = mcpWorker.claim(publication, UUID.randomUUID()); assertNotNull(command);
        assertNull(mcpWorker.claim(publication, UUID.randomUUID())); assertNull(mcpWorker.guardAndCredential(command));
        var stale = new com.agentplatform.domain.capability.McpProbeRepository.Command(command.spaceId(), command.actorId(), command.probeId(),
                command.connectorId(), command.configurationRevision(), command.ownerId(), command.generation(), command.fence()+1,
                command.endpoint(), command.authKind(), command.apiKeyHeader(), command.credentialId(), command.credentialRevision(), command.policyHash(), command.traceId());
        assertThrows(com.agentplatform.domain.files.FileFailure.class, () -> mcpWorker.guardAndCredential(stale));
        String schema = "{\"type\":\"object\"}"; String schemaHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(schema.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        mcpWorker.ready(command, List.of(new com.agentplatform.domain.capability.McpProbeRepository.Tool("fixture", "test", schema, schemaHash)));
        assertEquals("READY", connectors.probe(alice, probe.id()).state());
        UUID discovered = connectors.probe(alice, probe.id()).resultVersionId();
        assertEquals(1, mcpVersions.get(alice, config.id(), discovered).tools().size());
        UUID enable = UUID.randomUUID();
        assertEquals(1, mcpVersions.change(alice, config.id(), discovered, 0, enable, trace).revision());
        assertEquals(1, mcpVersions.change(alice, config.id(), discovered, 0, enable, trace).revision());
        var selection = new Selection(config.id(), discovered);
        service.replace(alice, null, 0, false, List.of(selection));
        assertTrue(service.effective(alice, null).unavailable().isEmpty());
        assertEquals(2, mcpVersions.change(alice, config.id(), null, 1, UUID.randomUUID(), trace).revision());
        assertTrue(service.effective(alice, null).unavailable().contains(selection));
        assertThrows(com.agentplatform.domain.files.FileFailure.class, () -> mcpVersions.current(bob, config.id()));
        try (var connection = DriverManager.getConnection(url, "postgres", password); var query = connection.createStatement();
             var rows = query.executeQuery("SELECT schema_canonical,schema_hash FROM pf.mcp_connector_tool WHERE capability_id='" + config.id() + "'")) {
            assertTrue(rows.next()); assertEquals(schema, rows.getString(1)); assertEquals(schemaHash, rows.getString(2));
        }
        assertThrows(com.agentplatform.domain.files.FileFailure.class, () -> mcpWorker.ready(command, List.of()));
    }

    @Test void realBrokerAndObjectStoreCreateOneSkillVersion(@org.junit.jupiter.api.io.TempDir Path temporary) throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("R62_INTEGRATED_ACCEPTANCE")), "R62 PG/MQ/S3 integration NOT_RUN");
        int s3Port = Integer.parseInt(System.getenv("R62_S3_PORT"));
        int mqPort = Integer.parseInt(System.getenv("R62_AMQP_PORT"));
        String mqPassword = Files.readString(Path.of(System.getenv("R62_AMQP_PASSWORD_FILE"))).strip();
        var provider = software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("fixture", "fixture"));
        try (var s3 = com.agentplatform.infrastructure.storage.S3Clients.create(java.net.URI.create("http://127.0.0.1:" + s3Port), provider)) {
            String bucket = "r62-" + UUID.randomUUID().toString().replace("-", "");
            s3.createBucket(b -> b.bucket(bucket));
            var assets = new com.agentplatform.infrastructure.storage.S3AssetStore(s3, bucket);
            var bytes = new java.io.ByteArrayOutputStream();
            try (var zip = new java.util.zip.ZipOutputStream(bytes)) {
                zip.putNextEntry(new java.util.zip.ZipEntry("SKILL.md"));
                zip.write("---\nname: fixture-skill\ndescription: Deterministic fixture\n---\n\nFixture instructions.\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            byte[] archive = bytes.toByteArray();
            String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(archive));
            var actor = new com.agentplatform.domain.capability.SkillImportRepository.Actor(alice.userId(), alice.spaceId());
            var service = new SkillImportService(imports, assets);
            var upload = service.create(actor, UUID.randomUUID(), null, archive.length, hash);
            service.transfer(actor, upload.id(), archive.length, new java.io.ByteArrayInputStream(archive));
            service.complete(actor, upload.id(), UUID.randomUUID());
            var connection = new org.springframework.amqp.rabbit.connection.CachingConnectionFactory("127.0.0.1", mqPort);
            connection.setUsername("r62fixture"); connection.setPassword(mqPassword);
            connection.setPublisherConfirmType(org.springframework.amqp.rabbit.connection.CachingConnectionFactory.ConfirmType.CORRELATED);
            connection.setPublisherReturns(true);
            var admin = new org.springframework.amqp.rabbit.core.RabbitAdmin(connection);
            var exchange = new org.springframework.amqp.core.DirectExchange(com.agentplatform.infrastructure.capability.SpringSkillMessaging.EXCHANGE, true, false);
            var queue = org.springframework.amqp.core.QueueBuilder.durable(com.agentplatform.infrastructure.capability.SpringSkillMessaging.QUEUE).quorum().build();
            admin.declareExchange(exchange); admin.declareQueue(queue);
            admin.declareBinding(org.springframework.amqp.core.BindingBuilder.bind(queue).to(exchange).with(com.agentplatform.infrastructure.capability.SpringSkillMessaging.ROUTE));
            var timers = java.util.concurrent.Executors.newScheduledThreadPool(2);
            var completed = new java.util.concurrent.CountDownLatch(1); var duplicate = new java.util.concurrent.CountDownLatch(1);
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            var observed = new com.agentplatform.domain.capability.SkillValidationRepository() {
                public void guard(Command c) { worker.guard(c); }
                public void draft(Command c, Manifest m) { worker.draft(c, m); }
                public void rejected(Command c, String reason) { worker.rejected(c, reason); }
                public void unknown(Command c, String reason) { worker.unknown(c, reason); }
                public Publication nextPublication(UUID owner) { return worker.nextPublication(owner); }
                public void published(Publication p) { worker.published(p); }
                public Command claim(Publication p, UUID owner) { var command = worker.claim(p, owner); if (command == null) duplicate.countDown(); return command; }
                public void quarantine(UUID id, String hash) { worker.quarantine(id, hash); }
            };
            try (var messaging = new com.agentplatform.infrastructure.capability.SpringSkillMessaging(connection, observed)) {
                var execution = new SkillValidationExecution(worker, new SkillValidationService(worker, assets,
                        new com.agentplatform.infrastructure.capability.SkillArchiveValidator(), temporary), timers);
                messaging.start(command -> { calls.incrementAndGet(); try { execution.run(command); } finally { completed.countDown(); } });
                var publication = worker.nextPublication(UUID.randomUUID()); assertNotNull(publication);
                messaging.publish(publication); worker.published(publication);
                assertTrue(completed.await(40, java.util.concurrent.TimeUnit.SECONDS), "worker must process the durable handoff");
                assertEquals("DRAFT", imports.find(actor, upload.id()).state());
                assertEquals("DRAFT", versions.get(alice, upload.capabilityId(), upload.versionId()).state());
                messaging.publish(publication);
                assertTrue(duplicate.await(20, java.util.concurrent.TimeUnit.SECONDS), "duplicate delivery must reach the durable inbox check");
                assertEquals(1, calls.get(), "duplicate handoff must not run validation twice");
                assertEquals(hash, assets.inspect(upload.objectKey()).sha256());
            } finally { timers.shutdownNow(); connection.destroy(); }
        }
    }

    @Test void revokedCredentialStopsProbeBeforeDiscovery() {
        UUID trace = UUID.randomUUID(); UUID createRequest = UUID.randomUUID();
        var credential = connectorCredentials.create(alice, createRequest, "fixture-secret", trace);
        assertEquals(credential.id(), connectorCredentials.byRequest(alice, createRequest).id());
        assertEquals(404, assertThrows(com.agentplatform.domain.files.FileFailure.class,
                () -> connectorCredentials.byRequest(bob, createRequest)).status());
        var config = connectors.create(alice, UUID.randomUUID(), new McpConnectorService.Input("https://example.com/mcp", "BEARER", null,
                credential.id(), credential.revision()), trace);
        var probe = connectors.enqueue(alice, config.id(), config.revision(), UUID.randomUUID(), trace);
        var publication = mcpWorker.nextPublication(UUID.randomUUID()); mcpWorker.published(publication);
        var command = mcpWorker.claim(publication, UUID.randomUUID());
        byte[] key = new byte[32]; java.util.Arrays.fill(key, (byte) 7);
        assertEquals("fixture-secret", new com.agentplatform.infrastructure.chat.CredentialCipher(key).decryptConnector(mcpWorker.guardAndCredential(command)));
        connectorCredentials.revoke(alice, credential.id(), credential.revision(), UUID.randomUUID(), trace);
        assertEquals(403, assertThrows(com.agentplatform.domain.files.FileFailure.class, () -> mcpWorker.guardAndCredential(command)).status());
        mcpWorker.failed(command, "CONNECTOR_CREDENTIAL_UNAVAILABLE");
        assertEquals("FAILED", connectors.probe(alice, probe.id()).state());
    }

    @Test void revokedCredentialBeforeClaimEndsWithoutCallingRemote() {
        UUID trace = UUID.randomUUID(); var credential = connectorCredentials.create(alice, UUID.randomUUID(), "fixture-secret", trace);
        var config = connectors.create(alice, UUID.randomUUID(), new McpConnectorService.Input("https://example.com/mcp", "BEARER", null,
                credential.id(), credential.revision()), trace);
        var probe = connectors.enqueue(alice, config.id(), config.revision(), UUID.randomUUID(), trace);
        connectorCredentials.revoke(alice, credential.id(), credential.revision(), UUID.randomUUID(), trace);
        var publication = mcpWorker.nextPublication(UUID.randomUUID()); mcpWorker.published(publication);
        assertNull(mcpWorker.claim(publication, UUID.randomUUID()));
        var result = connectors.probe(alice, probe.id());
        assertEquals("FAILED", result.state()); assertEquals("CONNECTOR_CREDENTIAL_UNAVAILABLE", result.errorCode());
    }

    @Test void failedProxyDoesNotFallbackAndLeavesProbeBlocked() throws Exception {
        UUID trace = UUID.randomUUID();
        var config = connectors.create(alice, UUID.randomUUID(), new McpConnectorService.Input("https://fixture.invalid/mcp", "NONE", null, null, null), trace);
        var probe = connectors.enqueue(alice, config.id(), config.revision(), UUID.randomUUID(), trace);
        var publication = mcpWorker.nextPublication(UUID.randomUUID()); mcpWorker.published(publication);
        var command = mcpWorker.claim(publication, UUID.randomUUID());
        try (var listener = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"));
             var timers = java.util.concurrent.Executors.newScheduledThreadPool(1)) {
            var observed = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try (var socket = listener.accept()) {
                    socket.setSoTimeout(5000);
                    var input = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII));
                    String first = input.readLine();
                    socket.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush(); return first;
                } catch (Exception failure) { throw new IllegalStateException(failure); }
            });
            var discovery = new com.agentplatform.infrastructure.capability.McpDiscovery(new java.net.InetSocketAddress("127.0.0.1", listener.getLocalPort()));
            new McpProbeExecution(mcpWorker, discovery, new com.agentplatform.infrastructure.chat.CredentialCipher(new byte[32]), timers).run(command);
            assertTrue(observed.get(10, java.util.concurrent.TimeUnit.SECONDS).startsWith("CONNECT fixture.invalid:443 "));
            var result = connectors.probe(alice, probe.id());
            assertEquals("BLOCKED", result.state()); assertEquals("MCP_PROBE_RESULT_UNKNOWN", result.errorCode());
        }
    }

    @Test void connectorProbeFreezesSettingsAndRevokedCredentialsAreRejected() throws Exception {
        UUID trace = UUID.randomUUID(), request = UUID.randomUUID();
        var credential = connectorCredentials.create(alice, UUID.randomUUID(), "fixture-secret", trace);
        var input = new McpConnectorService.Input("https://example.com/mcp", "BEARER", null, credential.id(), credential.revision());
        var config = connectors.create(alice, request, input, trace);
        assertEquals(config, connectors.create(alice, request, input, trace));
        assertEquals(409, assertThrows(com.agentplatform.domain.files.FileFailure.class,
                () -> connectors.create(alice, request, new McpConnectorService.Input("https://example.org/mcp", "NONE", null, null, null), trace)).status());
        assertEquals(404, assertThrows(com.agentplatform.domain.files.FileFailure.class, () -> connectors.get(bob, config.id())).status());
        UUID probeKey = UUID.randomUUID();
        var probe = connectors.enqueue(alice, config.id(), config.revision(), probeKey, trace);
        assertEquals(probe.id(), connectors.enqueue(alice, config.id(), config.revision(), probeKey, trace).id());
        try (var connection = DriverManager.getConnection(url, "postgres", password); var query = connection.createStatement();
             var rows = query.executeQuery("SELECT count(*) FROM pf.mcp_probe_outbox WHERE probe_id='" + probe.id() + "'")) {
            assertTrue(rows.next()); assertEquals(1, rows.getInt(1));
        }
        connectorCredentials.revoke(alice, credential.id(), credential.revision(), UUID.randomUUID(), trace);
        assertEquals(422, assertThrows(com.agentplatform.domain.files.FileFailure.class,
                () -> connectors.enqueue(alice, config.id(), config.revision(), UUID.randomUUID(), trace)).status());
        assertEquals(422, assertThrows(com.agentplatform.domain.files.FileFailure.class,
                () -> connectors.create(alice, UUID.randomUUID(), new McpConnectorService.Input("https://127.0.0.1/mcp", "NONE", null, null, null), trace)).status());
    }
}
