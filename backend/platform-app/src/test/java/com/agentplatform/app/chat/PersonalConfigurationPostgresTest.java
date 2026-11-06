package com.agentplatform.app.chat;

import static org.junit.jupiter.api.Assertions.*;
import com.agentplatform.domain.chat.ChatFailure;
import com.agentplatform.domain.chat.WorkspaceConfigurationRepository;
import com.agentplatform.infrastructure.adapter.repository.chat.MyBatisWorkspaceConfigurationRepository;
import com.agentplatform.infrastructure.dao.chat.WorkspaceConfigurationMapper;
import com.agentplatform.infrastructure.dao.typehandler.UuidTypeHandler;
import com.agentplatform.types.TaskPermissionData.Mode;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real isolated PostgreSQL: migration, mapper loading, transaction and scoped role. */
class PersonalConfigurationPostgresTest {
    static String url, password;
    HikariDataSource adminPool, apiPool;
    EffectiveConfigService service;
    com.agentplatform.infrastructure.adapter.repository.chat.SubmissionIdentityRepository submissions;
    TransactionTemplate apiTransactions;
    WorkspaceConfigurationMapper configurationMapper;
    WorkspaceConfigurationRepository.Actor alice, bob;
    UUID workspace;

    static void sql(String statement) throws Exception {
        try (var connection=DriverManager.getConnection(url,"postgres",password); var query=connection.createStatement()) { query.execute(statement); }
    }
    @BeforeAll static void migrate() throws Exception {
        url=System.getenv("R61_TEST_JDBC_URL");
        Assumptions.assumeTrue(url!=null,"R61 isolated PostgreSQL NOT_RUN");
        int port=Integer.parseInt(System.getenv("R61_TEST_PORT"));
        assertEquals("jdbc:postgresql://127.0.0.1:"+port+"/r61_test?sslmode=disable",url);
        assertEquals("1",System.getenv("R61_ACCEPTANCE"));
        password=Files.readString(Path.of(System.getenv("R61_TEST_PASSWORD_FILE"))).strip();
        var base=Flyway.configure().dataSource(url,"postgres",password).defaultSchema("pf").schemas("pf","pf_runtime").cleanDisabled(false).load();
        base.clean();
        var old=Flyway.configure().dataSource(url,"postgres",password).defaultSchema("pf").schemas("pf","pf_runtime").target("21").load();
        old.migrate(); assertEquals("21",old.info().current().getVersion().toString());
        base.migrate(); base.validate(); assertEquals("22",base.info().current().getVersion().toString());
        base.clean();base.migrate();base.validate();assertEquals("22",base.info().current().getVersion().toString());
        try (var connection=DriverManager.getConnection(url,"postgres",password);var query=connection.createStatement();
             var rows=query.executeQuery("SELECT schema_version FROM pf.baseline_version WHERE id=1")) {
            assertTrue(rows.next());assertEquals(1,rows.getInt(1));
        }
        sql("CREATE ROLE ap_api NOLOGIN; GRANT USAGE ON SCHEMA pf TO ap_api; GRANT SELECT ON pf.space,pf.model_definition TO ap_api; " +
            "GRANT SELECT ON pf.workspace TO ap_api; GRANT UPDATE(version) ON pf.workspace TO ap_api; GRANT EXECUTE ON FUNCTION pf.authorize_workspace_actor(uuid,uuid) TO ap_api; " +
            "GRANT EXECUTE ON FUNCTION pf.authorize_chat_actor(uuid,uuid) TO ap_api; " +
            "GRANT EXECUTE ON FUNCTION pf.lock_r61_model(uuid) TO ap_api; " +
            "GRANT SELECT,INSERT,UPDATE,DELETE ON pf.user_preference TO ap_api; " +
            "GRANT SELECT,INSERT ON pf.idempotency_record TO ap_api; " +
            "GRANT SELECT,INSERT,UPDATE ON pf.user_preference_revision TO ap_api; " +
            "GRANT SELECT ON pf.model_binding,pf.user_model_credential TO ap_api; GRANT UPDATE(version) ON pf.user_model_credential TO ap_api; " +
            "GRANT SELECT(id,scope_type,space_id,owner_user_id,status,revision) ON pf.credential TO ap_api; GRANT UPDATE(status) ON pf.credential TO ap_api; " +
            "GRANT SELECT,INSERT,UPDATE ON pf.workspace_configuration TO ap_api; GRANT INSERT ON pf.chat_audit TO ap_api");
    }
    private HikariDataSource pool(boolean api) {
        var config=new HikariConfig();config.setJdbcUrl(url);config.setUsername("postgres");config.setPassword(password);
        config.setMaximumPoolSize(4);config.setMinimumIdle(0);if(api)config.setConnectionInitSql("SET ROLE ap_api");
        return new HikariDataSource(config);
    }
    @BeforeEach void setup() throws Exception {
        sql("TRUNCATE pf.app_user CASCADE");
        adminPool=pool(false);apiPool=pool(true);
        var identity=com.agentplatform.app.identity.IdentityPersistenceFixture.create(adminPool);
        UUID first=identity.provision(UUID.randomUUID(),"r61-"+UUID.randomUUID(),"r61_"+UUID.randomUUID().toString().replace("-",""),"fixture-only",true);
        UUID other=identity.provision(UUID.randomUUID(),"r61-"+UUID.randomUUID(),"r61_"+UUID.randomUUID().toString().replace("-",""),"fixture-only",false);
        alice=new WorkspaceConfigurationRepository.Actor(first,identity.spaces(first).getFirst().id());
        bob=new WorkspaceConfigurationRepository.Actor(other,identity.spaces(other).getFirst().id());
        workspace=UUID.randomUUID();UUID operation=UUID.randomUUID();
        sql("BEGIN;INSERT INTO pf.workspace(id,space_id,actor_id,name,state,quota_bytes,quota_inodes,project_id,operation_id) VALUES('"+workspace+"','"+alice.spaceId()+"','"+first+"','fixture','READY',2147483648,100000,(SELECT coalesce(max(project_id),200000)+1 FROM pf.workspace),'"+operation+"');"+
            "INSERT INTO pf.workspace_operation(id,space_id,workspace_id,actor_id,command_id,status) VALUES('"+operation+"','"+alice.spaceId()+"','"+workspace+"','"+first+"','"+UUID.randomUUID()+"','COMMITTED');COMMIT");
        var factory=new SqlSessionFactoryBean();factory.setDataSource(apiPool);
        var configuration=new org.apache.ibatis.session.Configuration();configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);configuration.setCacheEnabled(false);
        configuration.getTypeHandlerRegistry().register(new UuidTypeHandler());factory.setConfiguration(configuration);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mybatis/mapper/**/*.xml"));
        var session=new SqlSessionTemplate(factory.getObject());
        var mapper=session.getMapper(WorkspaceConfigurationMapper.class);configurationMapper=mapper;
        apiTransactions=new TransactionTemplate(new JdbcTransactionManager(apiPool));
        service=new EffectiveConfigService(new MyBatisWorkspaceConfigurationRepository(mapper),apiTransactions,null);
        submissions=new com.agentplatform.infrastructure.adapter.repository.chat.SubmissionIdentityRepository(session.getMapper(com.agentplatform.infrastructure.dao.chat.SubmissionIdentityMapper.class));
    }
    @AfterEach void close() { if(apiPool!=null)apiPool.close();if(adminPool!=null)adminPool.close(); }
    @Test void workspaceOverrideUsesVersionAndCannotCrossPersonalSpace() {
        assertEquals(0,service.read(alice,workspace).version());
        var set=new WorkspaceConfigurationRepository.Patch(false,null,false,null,true,false,true,Mode.FULL);
        var saved=service.update(alice,workspace,0,set,UUID.randomUUID());
        assertEquals(1,saved.version());assertFalse(saved.overrides().planMode());assertEquals(Mode.FULL,saved.overrides().permissionMode());
        assertEquals(404,assertThrows(ChatFailure.class,()->service.read(bob,workspace)).status());
        assertEquals(412,assertThrows(ChatFailure.class,()->service.update(alice,workspace,0,set,UUID.randomUUID())).status());
        var clear=new WorkspaceConfigurationRepository.Patch(false,null,false,null,true,null,true,null);
        var cleared=service.update(alice,workspace,1,clear,UUID.randomUUID());
        assertEquals(2,cleared.version());assertNull(cleared.overrides().planMode());assertNull(cleared.overrides().permissionMode());
    }
    @Test void concurrentFirstWritesHaveExactlyOneWinner() throws Exception {
        var start=new java.util.concurrent.CountDownLatch(1);
        var request=new WorkspaceConfigurationRepository.Patch(false,null,false,null,true,true,false,null);
        java.util.function.Supplier<Integer> save=()->{
            try { start.await();service.update(alice,workspace,0,request,UUID.randomUUID());return 200; }
            catch(ChatFailure failure) { return failure.status(); }
            catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw new IllegalStateException(interrupted); }
        };
        var first=java.util.concurrent.CompletableFuture.supplyAsync(save);
        var second=java.util.concurrent.CompletableFuture.supplyAsync(save);
        start.countDown();
        assertEquals(java.util.Set.of(200,412),java.util.Set.of(first.get(15,java.util.concurrent.TimeUnit.SECONDS),second.get(15,java.util.concurrent.TimeUnit.SECONDS)));
        assertEquals(1,service.read(alice,workspace).version());
    }
    @Test void workspaceUpdateWaitsForPersonalConfigurationTransaction() throws Exception {
        var started=new java.util.concurrent.CountDownLatch(1);
        @SuppressWarnings("unchecked") var pending=new java.util.concurrent.CompletableFuture[1];
        apiTransactions.executeWithoutResult(status->{
            configurationMapper.lockUser(alice);
            pending[0]=java.util.concurrent.CompletableFuture.supplyAsync(()->{
                started.countDown();return service.update(alice,workspace,0,
                        new WorkspaceConfigurationRepository.Patch(false,null,false,null,true,true,false,null),UUID.randomUUID());
            });
            try {
                assertTrue(started.await(5,java.util.concurrent.TimeUnit.SECONDS));
                assertThrows(java.util.concurrent.TimeoutException.class,()->pending[0].get(300,java.util.concurrent.TimeUnit.MILLISECONDS));
            } catch(InterruptedException failure) { Thread.currentThread().interrupt();throw new IllegalStateException(failure); }
        });
        assertEquals(1,((WorkspaceConfigurationRepository.View)pending[0].get(5,java.util.concurrent.TimeUnit.SECONDS)).version());
    }
    @Test void disabledIdentityAndUnavailableModelCannotSave() throws Exception {
        var request=new WorkspaceConfigurationRepository.Patch(true,UUID.randomUUID(),false,null,false,null,false,null);
        assertEquals(422,assertThrows(ChatFailure.class,()->service.update(alice,workspace,0,request,UUID.randomUUID())).status());
        assertEquals(0,service.read(alice,workspace).version());
        sql("UPDATE pf.app_user SET status='DISABLED' WHERE id='"+alice.userId()+"'");
        assertEquals(404,assertThrows(ChatFailure.class,()->service.read(alice,workspace)).status());
    }
    @Test void personalDefaultsPreserveFalseAndClearToInheritance() {
        var repository=ChatPersistenceFixture.create(apiPool);
        var actor=new com.agentplatform.domain.chat.ChatData.Actor(alice.userId(),alice.spaceId());
        var saved=repository.preferences(actor,0,java.util.Map.of("defaultCredentialSource","USER","defaultPlanEnabled",false,"defaultApprovalMode","FULL"));
        assertEquals(false,saved.get("defaultPlanEnabled"));assertEquals("FULL",saved.get("defaultApprovalMode"));
        var clear=new java.util.HashMap<String,Object>();clear.put("defaultCredentialSource",null);clear.put("defaultPlanEnabled",null);
        var restored=repository.preferences(actor,1,clear);
        assertEquals(2L,restored.get("version"));assertEquals("PLATFORM",restored.get("defaultCredentialSource"));
        var overrides=(java.util.Map<?,?>)restored.get("overrides");assertFalse(overrides.containsKey("defaultPlanEnabled"));assertFalse(overrides.containsKey("defaultCredentialSource"));
        assertEquals("FULL",overrides.get("defaultApprovalMode"));
        assertEquals(412,assertThrows(ChatFailure.class,()->repository.preferences(actor,1,java.util.Map.of("defaultApprovalMode","RISK_BASED"))).status());
    }
    @Test void effectivePreviewUsesAllLayersAndDoesNotFallbackCredentials() {
        var admin=ChatPersistenceFixture.create(adminPool);
        var actor=new com.agentplatform.domain.chat.ChatData.Actor(alice.userId(),alice.spaceId());
        var model=UUID.fromString("0199e000-0000-7000-8000-000000000002");
        var credential=admin.importCredential(new com.agentplatform.infrastructure.chat.CredentialCipher(new byte[32]).encrypt(UUID.randomUUID(),"PLATFORM",null,null,"FIXTURE_ONLY"));
        admin.bind(model,actor,credential,"PLATFORM");
        var repository=ChatPersistenceFixture.create(apiPool,false,com.agentplatform.domain.chat.ContextPolicy.legacy(),true);
        repository.preferences(actor,0,java.util.Map.of("defaultPlanEnabled",true,"defaultApprovalMode","FULL"));
        service.update(alice,workspace,0,new WorkspaceConfigurationRepository.Patch(false,null,false,null,true,false,false,null),UUID.randomUUID());
        var preview=repository.effective(actor,new com.agentplatform.domain.chat.ConfigurationQuery(workspace,null,null,null,null));
        var values=(java.util.Map<?,?>)preview.get("values");var sources=(java.util.Map<?,?>)preview.get("sources");
        assertEquals(false,values.get("planMode"));assertEquals("WORKSPACE",sources.get("planMode"));
        assertEquals("FULL",values.get("permissionMode"));assertEquals("USER",sources.get("permissionMode"));assertEquals(true,preview.get("canSubmit"));
        var explicit=repository.effective(actor,new com.agentplatform.domain.chat.ConfigurationQuery(workspace,null,null,false,null));
        assertNotEquals(preview.get("configHash"),explicit.get("configHash"));
        var unavailable=repository.effective(actor,new com.agentplatform.domain.chat.ConfigurationQuery(workspace,null,com.agentplatform.domain.chat.ConfigurationResolver.CredentialSource.USER,null,null));
        assertEquals(false,unavailable.get("canSubmit"));assertEquals(java.util.List.of("CREDENTIAL_SOURCE_UNAVAILABLE"),unavailable.get("blockers"));
        var restricted=ChatPersistenceFixture.create(apiPool,false,com.agentplatform.domain.chat.ContextPolicy.legacy(),true,false,false);
        var blocked=restricted.effective(actor,new com.agentplatform.domain.chat.ConfigurationQuery(workspace,null,null,null,null));
        assertEquals(false,blocked.get("canSubmit"));assertTrue(((java.util.List<?>)blocked.get("blockers")).contains("CAPABILITY_NOT_AVAILABLE"));
        assertNotEquals(preview.get("configHash"),blocked.get("configHash"));
        repository.preferences(actor,1,java.util.Map.of("defaultPlanEnabled",true));
        assertEquals(preview.get("configHash"),repository.effective(actor,new com.agentplatform.domain.chat.ConfigurationQuery(workspace,null,null,null,null)).get("configHash"));
    }
    @Test void deploymentLockWinsAndExplicitConflictIsRejected() {
        var actor=new com.agentplatform.domain.chat.ChatData.Actor(alice.userId(),alice.spaceId());
        var admin=ChatPersistenceFixture.create(adminPool);var model=UUID.fromString("0199e000-0000-7000-8000-000000000002");
        var credential=admin.importCredential(new com.agentplatform.infrastructure.chat.CredentialCipher(new byte[32]).encrypt(UUID.randomUUID(),"PLATFORM",null,null,"FIXTURE_ONLY"));admin.bind(model,actor,credential,"PLATFORM");
        admin.preferences(actor,0,java.util.Map.of("defaultApprovalMode","FULL"));
        var locks=new com.agentplatform.domain.chat.ConfigurationResolver.Layer(null,null,null,Mode.RISK_BASED);
        var locked=ChatPersistenceFixture.create(apiPool,false,com.agentplatform.domain.chat.ContextPolicy.legacy(),true,true,true,locks);
        var preview=locked.effective(actor,new com.agentplatform.domain.chat.ConfigurationQuery(null,null,null,null,null));
        assertEquals("RISK_BASED",((java.util.Map<?,?>)preview.get("values")).get("permissionMode"));
        assertTrue(((java.util.List<?>)preview.get("lockedFields")).contains("PERMISSION_MODE"));
        assertEquals(422,assertThrows(ChatFailure.class,()->locked.effective(actor,new com.agentplatform.domain.chat.ConfigurationQuery(null,null,null,null,Mode.FULL))).status());
    }
    @Test void unavailableDatabaseCannotAuthorizeConfiguration() {
        var repository=ChatPersistenceFixture.create(apiPool,false,com.agentplatform.domain.chat.ContextPolicy.legacy(),true);
        var actor=new com.agentplatform.domain.chat.ChatData.Actor(alice.userId(),alice.spaceId());apiPool.close();
        assertEquals(503,assertThrows(ChatFailure.class,()->repository.effective(actor,new com.agentplatform.domain.chat.ConfigurationQuery(null,null,null,null,null))).status());
    }
    @Test void submissionIdentityKeepsLegacyAndCurrentDigestsSeparate() throws Exception {
        var actor=new com.agentplatform.domain.chat.ChatData.Actor(alice.userId(),alice.spaceId());
        var oldKey=UUID.randomUUID();var oldResource=UUID.randomUUID();var path="/conversations";
        sql("INSERT INTO pf.idempotency_record(user_id,space_id,method,canonical_path,key,body_hash,resource_id) VALUES('"+alice.userId()+"','"+alice.spaceId()+"','POST','"+path+"','"+oldKey+"','"+"a".repeat(64)+"','"+oldResource+"')");
        assertEquals(oldResource,apiTransactions.execute(status->submissions.lookup(actor,path,oldKey,"a".repeat(64),"b".repeat(64))));
        assertEquals(409,assertThrows(ChatFailure.class,()->apiTransactions.execute(status->submissions.lookup(actor,path,oldKey,"c".repeat(64),"b".repeat(64)))).status());
        var key=UUID.randomUUID();var resource=UUID.randomUUID();
        apiTransactions.executeWithoutResult(status->{assertNull(submissions.lookup(actor,path,key,null,"b".repeat(64)));submissions.remember(actor,path,key,"b".repeat(64),resource);});
        assertEquals(resource,apiTransactions.execute(status->submissions.lookup(actor,path,key,null,"b".repeat(64))));
        assertEquals(409,assertThrows(ChatFailure.class,()->apiTransactions.execute(status->submissions.lookup(actor,path,key,null,"c".repeat(64)))).status());
    }
    @Test void taskSubmissionFreezesResolvedSettingsAndReplaysBeforeNewDefaults() {
        var actor=new com.agentplatform.domain.chat.ChatData.Actor(alice.userId(),alice.spaceId());
        var repository=ChatPersistenceFixture.create(adminPool,false,com.agentplatform.domain.chat.ContextPolicy.legacy(),true);
        var model=UUID.fromString("0199e000-0000-7000-8000-000000000002");
        var credential=repository.importCredential(new com.agentplatform.infrastructure.chat.CredentialCipher(new byte[32]).encrypt(UUID.randomUUID(),"PLATFORM",null,null,"FIXTURE_ONLY"));
        repository.bind(model,actor,credential,"PLATFORM");
        repository.preferences(actor,0,java.util.Map.of("defaultPlanEnabled",true,"defaultApprovalMode","FULL"));
        service.update(alice,workspace,0,new WorkspaceConfigurationRepository.Patch(false,null,false,null,true,false,false,null),UUID.randomUUID());
        var query=new com.agentplatform.domain.chat.ConfigurationQuery(workspace,null,null,null,null);
        var preview=repository.effective(actor,query);var expected=(String)preview.get("configHash");
        var request=new com.agentplatform.domain.chat.TaskSubmissionFingerprint.Request("fixture","hello",workspace,null,null,null,null,expected,java.util.List.of());
        var key=UUID.randomUUID();var first=repository.firstV2(actor,key,request);
        var snapshot=repository.configuration(actor,first.task().id());var values=(java.util.Map<?,?>)snapshot.get("values");
        assertEquals(false,values.get("planMode"));assertEquals("FULL",values.get("permissionMode"));
        var resolution=(java.util.Map<?,?>)values.get("resolution");assertEquals(expected,resolution.get("effectiveHash"));
        repository.preferences(actor,1,java.util.Map.of("defaultApprovalMode","RISK_BASED"));
        assertEquals(first.task().id(),repository.firstV2(actor,key,request).task().id());
        assertEquals(412,assertThrows(ChatFailure.class,()->repository.firstV2(actor,UUID.randomUUID(),request)).status());
        assertEquals(snapshot,repository.configuration(actor,first.task().id()));
        var withoutExpected=new com.agentplatform.domain.chat.TaskSubmissionFingerprint.Request("another","hello",workspace,null,null,null,null,null,java.util.List.of());
        assertEquals(412,assertThrows(ChatFailure.class,()->repository.firstV2(actor,UUID.randomUUID(),withoutExpected,expected)).status());
        var newPreview=repository.effective(actor,query);
        var next=new com.agentplatform.domain.chat.TaskSubmissionFingerprint.Request(null,"next",workspace,null,null,null,null,(String)newPreview.get("configHash"),java.util.List.of());
        var nextKey=UUID.randomUUID();var second=repository.submitV2(actor,first.conversation().id(),nextKey,next);
        assertEquals(second.id(),repository.submitV2(actor,first.conversation().id(),nextKey,next).id());
        assertEquals("RISK_BASED",((java.util.Map<?,?>)repository.configuration(actor,second.id()).get("values")).get("permissionMode"));
    }
    @Test void priorLegacyRequestCanBeLocatedWithoutChangingItsSnapshot() {
        var actor=new com.agentplatform.domain.chat.ChatData.Actor(alice.userId(),alice.spaceId());
        var repository=ChatPersistenceFixture.create(adminPool,false,com.agentplatform.domain.chat.ContextPolicy.legacy(),true);
        var model=UUID.fromString("0199e000-0000-7000-8000-000000000002");
        var credential=repository.importCredential(new com.agentplatform.infrastructure.chat.CredentialCipher(new byte[32]).encrypt(UUID.randomUUID(),"PLATFORM",null,null,"FIXTURE_ONLY"));
        repository.bind(model,actor,credential,"PLATFORM");var key=UUID.randomUUID();
        var original=repository.firstAuthorized(actor,key,"legacy",model,"PLATFORM","hello",false,null,Mode.RISK_BASED);
        var request=new com.agentplatform.domain.chat.TaskSubmissionFingerprint.Request("legacy","hello",null,model,com.agentplatform.domain.chat.ConfigurationResolver.CredentialSource.PLATFORM,false,Mode.RISK_BASED,null,java.util.List.of());
        assertEquals(original.task().id(),repository.findFirstV2(actor,key,request).task().id());
        assertEquals("LEGACY",((java.util.Map<?,?>)((java.util.Map<?,?>)repository.configuration(actor,original.task().id()).get("values")).get("resolution")).get("resolverVersion"));
    }
    @Test void serviceReplaySkipsAdmissionEvenAfterDefaultsChange() throws Exception {
        var actor=new com.agentplatform.domain.chat.ChatData.Actor(alice.userId(),alice.spaceId());
        var repository=ChatPersistenceFixture.create(adminPool,false,com.agentplatform.domain.chat.ContextPolicy.legacy(),true);
        var model=UUID.fromString("0199e000-0000-7000-8000-000000000002");
        var credential=repository.importCredential(new com.agentplatform.infrastructure.chat.CredentialCipher(new byte[32]).encrypt(UUID.randomUUID(),"PLATFORM",null,null,"FIXTURE_ONLY"));
        repository.bind(model,actor,credential,"PLATFORM");
        var factory=new SqlSessionFactoryBean();factory.setDataSource(adminPool);var config=new org.apache.ibatis.session.Configuration();
        config.setMapUnderscoreToCamelCase(true);config.getTypeHandlerRegistry().register(new UuidTypeHandler());factory.setConfiguration(config);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mybatis/mapper/**/*.xml"));
        var mapper=new SqlSessionTemplate(factory.getObject()).getMapper(com.agentplatform.infrastructure.dao.files.TaskAttachmentMapper.class);
        var admission=org.mockito.Mockito.mock(com.agentplatform.app.attachment.AttachmentAdmission.class);
        var service=new ChatService(repository,new com.agentplatform.infrastructure.chat.ChatCursor(new byte[32]));
        service.configureAttachments(new com.agentplatform.app.attachment.AttachmentSubmission(mapper,new TransactionTemplate(new JdbcTransactionManager(adminPool)),admission));
        var caller=new com.agentplatform.api.chat.ChatApi.Caller(alice.userId().toString(),alice.spaceId().toString());
        var input=new com.agentplatform.api.chat.ChatApi.Submission("hello",null,null,null,null,null,null,java.util.List.of());var key=UUID.randomUUID().toString();
        var first=service.firstV2(caller,key,"service",input);
        repository.preferences(actor,0,java.util.Map.of("defaultPlanEnabled",true));
        assertEquals(first.initialTask().taskId(),service.firstV2(caller,key,"service",input).initialTask().taskId());
        org.mockito.Mockito.verify(admission,org.mockito.Mockito.times(1)).requireFits(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.isNull(),org.mockito.ArgumentMatchers.eq("hello"),org.mockito.ArgumentMatchers.eq(false),org.mockito.ArgumentMatchers.eq(false),org.mockito.ArgumentMatchers.anyList());
        var changed=new com.agentplatform.api.chat.ChatApi.Submission("changed",null,null,null,null,null,null,java.util.List.of());
        assertEquals(409,assertThrows(ChatFailure.class,()->service.firstV2(caller,key,"service",changed)).status());
    }
    @Test void concurrentFirstSubmissionsCreateExactlyOneTask() throws Exception {
        var actor=new com.agentplatform.domain.chat.ChatData.Actor(alice.userId(),alice.spaceId());
        var repository=ChatPersistenceFixture.create(adminPool,false,com.agentplatform.domain.chat.ContextPolicy.legacy(),true);
        var model=UUID.fromString("0199e000-0000-7000-8000-000000000002");
        var credential=repository.importCredential(new com.agentplatform.infrastructure.chat.CredentialCipher(new byte[32]).encrypt(UUID.randomUUID(),"PLATFORM",null,null,"FIXTURE_ONLY"));
        repository.bind(model,actor,credential,"PLATFORM");
        var request=new com.agentplatform.domain.chat.TaskSubmissionFingerprint.Request("concurrent","hello",null,null,null,null,null,null,java.util.List.of());
        var key=UUID.randomUUID();var start=new java.util.concurrent.CountDownLatch(1);
        java.util.function.Supplier<UUID> submit=()->{try{start.await();return repository.firstV2(actor,key,request).task().id();}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}};
        var first=java.util.concurrent.CompletableFuture.supplyAsync(submit);var second=java.util.concurrent.CompletableFuture.supplyAsync(submit);start.countDown();
        assertEquals(first.get(15,java.util.concurrent.TimeUnit.SECONDS),second.get(15,java.util.concurrent.TimeUnit.SECONDS));
        try(var connection=DriverManager.getConnection(url,"postgres",password);var statement=connection.createStatement();var rows=statement.executeQuery("SELECT count(*) FROM pf.task")){assertTrue(rows.next());assertEquals(1,rows.getInt(1));}
    }
    @Test void attachmentAdmissionUsesFrozenConfigurationAndReplayDoesNotReadAgain() throws Exception {
        byte[] text="R61 附件正文".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text));
        var factory=new SqlSessionFactoryBean();factory.setDataSource(adminPool);var configuration=new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);configuration.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);configuration.setCacheEnabled(false);
        configuration.getTypeHandlerRegistry().register(new UuidTypeHandler());factory.setConfiguration(configuration);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mybatis/mapper/**/*.xml"));
        var session=new SqlSessionTemplate(factory.getObject());var transactions=new TransactionTemplate(new JdbcTransactionManager(adminPool));
        var fileActor=new com.agentplatform.domain.files.FileData.Actor(alice.userId(),alice.spaceId());
        var attachments=new com.agentplatform.app.attachment.TransactionalAttachmentRepository(
                new com.agentplatform.infrastructure.adapter.repository.files.MyBatisAttachmentRepository(session.getMapper(com.agentplatform.infrastructure.dao.files.AttachmentMapper.class),session.getMapper(com.agentplatform.infrastructure.dao.files.FileMapper.class)),transactions);
        var upload=attachments.create(fileActor,UUID.randomUUID(),hash,new com.agentplatform.domain.attachment.AttachmentData.Create(UUID.randomUUID(),"fixture.txt",text.length,hash));
        UUID transfer=UUID.randomUUID();attachments.begin(fileActor,upload.id(),transfer);attachments.verified(fileActor,upload.id(),transfer);var attachment=attachments.complete(fileActor,upload.id());
        String image="sha256:"+"a".repeat(64),policy="b".repeat(64);
        var extraction=new com.agentplatform.app.preview.TransactionalPreviewRepository(
                new com.agentplatform.infrastructure.adapter.repository.files.MyBatisPreviewRepository(session.getMapper(com.agentplatform.infrastructure.dao.files.ExtractionMapper.class),session.getMapper(com.agentplatform.infrastructure.dao.files.FileMapper.class),image,"asset-r61-fixture",policy,true),transactions);
        var job=extraction.createAttachment(fileActor,attachment.id(),"TEXT_EXTRACT",UUID.randomUUID(),hash);
        var publication=extraction.publications(UUID.randomUUID()).getFirst();extraction.published(publication,true);var command=extraction.claim(publication,UUID.randomUUID());
        UUID runner=UUID.randomUUID();assertTrue(extraction.runnerClaim(command,runner));extraction.runnerGuard(command,runner);
        var receipt=new com.agentplatform.domain.preview.PreviewData.Receipt(command.commandId(),job.id(),1,command.fence(),runner,"READY","TEXT","text/plain",text.length,hash,null,command.outputObjectKey(),false,null,false,
                new com.agentplatform.domain.preview.PreviewData.Stop(null,null,image,null,true,true,true,java.time.Instant.now()));
        extraction.runnerReceipt(command,runner,receipt);extraction.complete(command,receipt);
        var reads=new java.util.concurrent.atomic.AtomicInteger();
        com.agentplatform.domain.files.AssetStore assets=new com.agentplatform.domain.files.AssetStore(){
            @Override public Content open(String key,Long first,Long last){assertEquals(command.outputObjectKey(),key);reads.incrementAndGet();return new Content(new java.io.ByteArrayInputStream(text),text.length,text.length);}
            @Override public Identity put(String key,long bytes,String expected,java.io.InputStream content){throw new UnsupportedOperationException();}
            @Override public Identity inspect(String key){throw new UnsupportedOperationException();}
            @Override public void deleteUncommitted(String key){throw new UnsupportedOperationException();}
        };
        var context=com.agentplatform.domain.chat.ContextPolicy.current(262144,4096);
        var repository=ChatPersistenceFixture.create(adminPool,false,context,true);var actor=new com.agentplatform.domain.chat.ChatData.Actor(alice.userId(),alice.spaceId());
        var model=UUID.fromString("0199e000-0000-7000-8000-000000000002");
        var credential=repository.importCredential(new com.agentplatform.infrastructure.chat.CredentialCipher(new byte[32]).encrypt(UUID.randomUUID(),"PLATFORM",null,null,"FIXTURE_ONLY"));repository.bind(model,actor,credential,"PLATFORM");
        var mapper=session.getMapper(com.agentplatform.infrastructure.dao.files.TaskAttachmentMapper.class);
        var admission=new com.agentplatform.app.attachment.AttachmentAdmission(mapper,transactions,repository,assets,context,false);
        var chat=new ChatService(repository,new com.agentplatform.infrastructure.chat.ChatCursor(new byte[32]));chat.configureAttachments(new com.agentplatform.app.attachment.AttachmentSubmission(mapper,transactions,admission));
        var caller=new com.agentplatform.api.chat.ChatApi.Caller(alice.userId().toString(),alice.spaceId().toString());
        var ref=new com.agentplatform.types.AttachmentReference("CHAT_ATTACHMENT",null,null,attachment.id().toString(),job.id().toString());
        var preview=repository.effective(actor,new com.agentplatform.domain.chat.ConfigurationQuery(null,null,null,null,null));
        repository.preferences(actor,0,java.util.Map.of("defaultPlanEnabled",true));
        var stale=new com.agentplatform.api.chat.ChatApi.Submission("hello",null,null,null,null,null,(String)preview.get("configHash"),java.util.List.of(ref));
        assertEquals(412,assertThrows(ChatFailure.class,()->chat.firstV2(caller,UUID.randomUUID().toString(),"attachment",stale)).status());assertEquals(0,reads.get());
        var current=repository.effective(actor,new com.agentplatform.domain.chat.ConfigurationQuery(null,null,null,null,null));
        var request=new com.agentplatform.api.chat.ChatApi.Submission("hello",null,null,null,null,null,(String)current.get("configHash"),java.util.List.of(ref));
        var key=UUID.randomUUID().toString();var first=chat.firstV2(caller,key,"attachment",request);assertEquals(1,reads.get());
        var values=(java.util.Map<?,?>)repository.configuration(actor,UUID.fromString(first.initialTask().taskId())).get("values");assertEquals(true,values.get("planMode"));
        assertEquals(1,repository.snapshot(actor,UUID.fromString(first.id())).messages().stream().flatMap(message->message.blocks().stream()).filter(block->"FILE_REFERENCE".equals(block.get("type"))).count());
        repository.preferences(actor,1,java.util.Map.of("defaultPlanEnabled",false));
        assertEquals(first.initialTask().taskId(),chat.firstV2(caller,key,"attachment",request).initialTask().taskId());assertEquals(1,reads.get());
    }
}
