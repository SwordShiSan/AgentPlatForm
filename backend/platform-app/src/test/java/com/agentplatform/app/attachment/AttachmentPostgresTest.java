package com.agentplatform.app.attachment;

import com.agentplatform.app.identity.IdentityPersistenceFixture;
import com.agentplatform.app.preview.TransactionalPreviewRepository;
import com.agentplatform.domain.attachment.*;
import com.agentplatform.domain.attachment.AttachmentData.*;
import com.agentplatform.domain.files.FileData.Actor;
import com.agentplatform.domain.files.FileFailure;
import com.agentplatform.domain.preview.*;
import com.agentplatform.domain.preview.PreviewData.*;
import com.agentplatform.infrastructure.adapter.repository.files.*;
import com.agentplatform.infrastructure.dao.files.*;
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

/** Real PostgreSQL/typed mapper facts. Synthetic receipts are not container-stop evidence. */
class AttachmentPostgresTest {
    private static String url,password;
    private static final String IMAGE="sha256:"+"a".repeat(64),POLICY="b".repeat(64),HASH="c".repeat(64);
    private HikariDataSource pool;private AttachmentRepository attachments;private PreviewRepository extraction;private Actor alice,bob;
    private static Flyway flyway(String target){var config=Flyway.configure().dataSource(url,"postgres",password).defaultSchema("pf").schemas("pf","pf_runtime").cleanDisabled(false);if(target!=null)config.target(target);return config.load();}
    @BeforeAll static void migrate()throws Exception {
        url=System.getenv("R54_TEST_JDBC_URL");Assumptions.assumeTrue(url!=null,"R54 independent PostgreSQL NOT_RUN");
        assertEquals("1",System.getenv("R54_ACCEPTANCE"));assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:1545[45]/workbench_test\\?sslmode=disable"));
        password=Files.readString(Path.of(System.getenv("R54_TEST_PASSWORD_FILE"))).strip();
        flyway(null).clean();flyway("20").migrate();assertEquals(20,count("SELECT max(version::int) FROM pf.flyway_schema_history WHERE success"));
        flyway(null).migrate();assertEquals(21,count("SELECT max(version::int) FROM pf.flyway_schema_history WHERE success"));
        assertEquals(1,count("SELECT schema_version FROM pf.baseline_version WHERE id=1"));
    }
    private static void sql(String text)throws Exception{try(var c=DriverManager.getConnection(url,"postgres",password);var s=c.createStatement()){s.execute(text);}}
    private static long count(String text)throws Exception{try(var c=DriverManager.getConnection(url,"postgres",password);var s=c.createStatement();var rows=s.executeQuery(text)){rows.next();return rows.getLong(1);}}
    private static SqlSessionTemplate session(HikariDataSource pool)throws Exception {
        var factory=new SqlSessionFactoryBean();factory.setDataSource(pool);var configuration=new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);configuration.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);configuration.setCacheEnabled(false);
        configuration.setDefaultStatementTimeout(5);configuration.getTypeHandlerRegistry().register(new UuidTypeHandler());factory.setConfiguration(configuration);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mybatis/mapper/**/*.xml"));return new SqlSessionTemplate(factory.getObject());
    }
    @BeforeEach void setup()throws Exception {
        sql("TRUNCATE pf.app_user CASCADE");sql("UPDATE pf.heavy_runtime_slot SET kind=NULL,reference_id=NULL,generation=NULL,owner_id=NULL");
        sql("INSERT INTO pf.quota_policy(id,metric,limit_value) VALUES('0199e000-0000-7000-8000-000000000015','TOKENS',1000000),('0199e000-0000-7000-8000-000000000016','COST_MICROS',NULL)");
        sql("INSERT INTO pf.execution_slot(id) VALUES(1) ON CONFLICT DO NOTHING");
        var config=new HikariConfig();config.setJdbcUrl(url);config.setUsername("postgres");config.setPassword(password);config.setMaximumPoolSize(4);config.setMinimumIdle(1);pool=new HikariDataSource(config);
        var session=session(pool);var tx=new TransactionTemplate(new JdbcTransactionManager(pool));
        attachments=new TransactionalAttachmentRepository(new MyBatisAttachmentRepository(session.getMapper(AttachmentMapper.class),session.getMapper(FileMapper.class)),tx);
        extraction=new TransactionalPreviewRepository(new MyBatisPreviewRepository(session.getMapper(ExtractionMapper.class),session.getMapper(FileMapper.class),IMAGE,"asset-r54-v1",POLICY,true),tx);
        var identity=IdentityPersistenceFixture.create(pool);
        UUID user=identity.provision(UUID.randomUUID(),"r54-alice","r54_alice","fixture-not-login",true),other=identity.provision(UUID.randomUUID(),"r54-bob","r54_bob","fixture-not-login",false);
        alice=new Actor(user,identity.spaces(user).getFirst().id());bob=new Actor(other,identity.spaces(other).getFirst().id());
    }
    @AfterEach void close(){if(pool!=null)pool.close();}
    private Upload upload(){return attachments.create(alice,UUID.randomUUID(),HASH,new Create(UUID.randomUUID(),"中文.xlsx",8,HASH));}
    private Attachment available(){var upload=upload();UUID owner=UUID.randomUUID();attachments.begin(alice,upload.id(),owner);attachments.verified(alice,upload.id(),owner);return attachments.complete(alice,upload.id());}
    @Test void originalBytesHaveNoWorkspaceAndQuotaSettlesOnce()throws Exception {
        UUID key=UUID.randomUUID();var body=new Create(UUID.randomUUID(),"中文.xlsx",8,HASH);var upload=attachments.create(alice,key,HASH,body);
        assertEquals(upload.id(),attachments.create(alice,key,HASH,body).id());assertEquals(upload.id(),attachments.request(alice,key).id());
        assertEquals(409,assertThrows(FileFailure.class,()->attachments.create(alice,key,"d".repeat(64),body)).status());
        UUID owner=UUID.randomUUID();attachments.begin(alice,upload.id(),owner);attachments.verified(alice,upload.id(),owner);var attachment=attachments.complete(alice,upload.id());
        assertEquals("AVAILABLE",attachment.status());assertEquals(attachment.id(),attachments.complete(alice,upload.id()).id());
        assertEquals(0,count("SELECT count(*) FROM pf.workspace"));assertEquals(0,count("SELECT count(*) FROM pf.workspace_environment"));
        assertEquals(8,count("SELECT committed_bytes FROM pf.space_storage WHERE space_id='"+alice.spaceId()+"'"));
        assertEquals(0,count("SELECT reserved_bytes FROM pf.space_storage WHERE space_id='"+alice.spaceId()+"'"));
        assertEquals(404,assertThrows(FileFailure.class,()->attachments.get(bob,attachment.id())).status());
    }
    @Test void unknownTransferCannotBeStartedOrCompletedAgain() {
        var row=upload();UUID owner=UUID.randomUUID();attachments.begin(alice,row.id(),owner);attachments.uncertain(alice,row.id(),owner);
        assertEquals("UNKNOWN",attachments.upload(alice,row.id()).status());assertThrows(FileFailure.class,()->attachments.begin(alice,row.id(),UUID.randomUUID()));assertThrows(FileFailure.class,()->attachments.complete(alice,row.id()));
    }
    private HikariDataSource rolePool(String role){
        var config=new HikariConfig();config.setJdbcUrl(url);config.setUsername("postgres");config.setPassword(password);
        config.setConnectionInitSql("SET ROLE "+role);config.setMaximumPoolSize(2);config.setMinimumIdle(0);return new HikariDataSource(config);
    }
    private PreviewRepository extractionAs(HikariDataSource data)throws Exception {
        var session=session(data);return new TransactionalPreviewRepository(new MyBatisPreviewRepository(session.getMapper(ExtractionMapper.class),session.getMapper(FileMapper.class),IMAGE,"asset-r54-v1",POLICY,true),new TransactionTemplate(new JdbcTransactionManager(data)));
    }
    @Test void actualRuntimeRolesCanHandOffAndCommitButCannotReadOtherUsers()throws Exception {
        Path root=Path.of("").toAbsolutePath();while(root!=null&&!Files.isDirectory(root.resolve("deploy/config")))root=root.getParent();
        assertNotNull(root,"Repository config directory required");var directory=root.resolve("deploy/config");
        var grants=List.of("r1-runtime-grants.sql","r2-runtime-grants.sql","r3-workspace-grants.sql","r3-environment-grants.sql","r3-file-grants.sql","r4-runtime-grants.sql","r4-permission-grants.sql","r4-approval-grants.sql","r4-workspace-tools-grants.sql","r5-file-grants.sql","r5-preview-grants.sql","r5-workbench-grants.sql");
        for(var name:grants){
            String content=Files.readString(directory.resolve(name));var matcher=java.util.regex.Pattern.compile("\\bap_[a-z_]+\\b").matcher(content);
            while(matcher.find()){String role=matcher.group();sql("DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='"+role+"') THEN CREATE ROLE "+role+";END IF;END $$;");}
            sql(content);
        }
        try(var api=rolePool("ap_api");var worker=rolePool("ap_worker");var runner=rolePool("ap_runner")){
            var session=session(api);var repository=new TransactionalAttachmentRepository(new MyBatisAttachmentRepository(session.getMapper(AttachmentMapper.class),session.getMapper(FileMapper.class)),new TransactionTemplate(new JdbcTransactionManager(api)));
            var upload=repository.create(alice,UUID.randomUUID(),HASH,new Create(UUID.randomUUID(),"roles.txt",8,HASH));
            UUID transport=UUID.randomUUID();repository.begin(alice,upload.id(),transport);repository.verified(alice,upload.id(),transport);var attachment=repository.complete(alice,upload.id());
            assertEquals(404,assertThrows(FileFailure.class,()->repository.get(bob,attachment.id())).status());
            var apiJobs=extractionAs(api);var workerJobs=extractionAs(worker);var runnerJobs=extractionAs(runner);
            var job=apiJobs.createAttachment(alice,attachment.id(),"TEXT_EXTRACT",UUID.randomUUID(),HASH);
            var publication=apiJobs.publications(UUID.randomUUID()).getFirst();apiJobs.published(publication,true);
            var command=workerJobs.claim(publication,UUID.randomUUID());assertNotNull(command);
            UUID owner=UUID.randomUUID();assertTrue(runnerJobs.runnerClaim(command,owner));runnerJobs.runnerGuard(command,owner);
            var receipt=new Receipt(command.commandId(),job.id(),1,command.fence(),owner,"READY","TEXT","text/plain",8,HASH,null,command.outputObjectKey(),false,null,false,new Stop(null,null,IMAGE,null,true,true,true,Instant.now()));
            runnerJobs.runnerReceipt(command,owner,receipt);workerJobs.complete(command,receipt);
            assertEquals("READY",apiJobs.get(alice,job.id()).state());assertEquals(16,repository.usage(alice).usedBytes());
        }
    }
    @Test void extractionUsesOneSharedSlotAndDurableReceiptBeforeSettlement()throws Exception {
        var attachment=available();var job=extraction.createAttachment(alice,attachment.id(),"TEXT_EXTRACT",UUID.randomUUID(),HASH);
        assertNull(job.workspaceId());assertNull(job.fileId());assertEquals(attachment.id(),job.attachmentId());
        assertEquals(job.id(),extraction.createAttachment(alice,attachment.id(),"TEXT_EXTRACT",UUID.randomUUID(),HASH).id());
        var publication=extraction.publications(UUID.randomUUID()).getFirst();extraction.published(publication,true);var command=extraction.claim(publication,UUID.randomUUID());
        assertNotNull(command);assertNull(extraction.claim(publication,UUID.randomUUID()));
        assertEquals(1,count("SELECT count(*) FROM pf.heavy_runtime_slot WHERE kind='EXTRACTION'"));
        UUID runner=UUID.randomUUID();assertTrue(extraction.runnerClaim(command,runner));extraction.unknown(command,"EXTRACTION_RESULT_UNKNOWN");
        assertThrows(FileFailure.class,()->extraction.runnerClaim(command,UUID.randomUUID()));
        var receipt=new Receipt(command.commandId(),job.id(),1,command.fence(),runner,"READY","TEXT","text/plain",8,HASH,null,command.outputObjectKey(),false,null,false,new Stop(null,null,IMAGE,null,true,true,true,Instant.now()));
        assertThrows(FileFailure.class,()->extraction.complete(command,receipt));extraction.runnerReceipt(command,runner,receipt);extraction.complete(command,receipt);extraction.complete(command,receipt);
        assertEquals("READY",extraction.get(alice,job.id()).state());assertEquals(0,count("SELECT count(*) FROM pf.heavy_runtime_slot WHERE kind IS NOT NULL"));
        assertEquals(16,count("SELECT committed_bytes FROM pf.space_storage WHERE space_id='"+alice.spaceId()+"'"));
        assertEquals(0,count("SELECT reserved_bytes FROM pf.space_storage WHERE space_id='"+alice.spaceId()+"'"));
        assertEquals(404,assertThrows(FileFailure.class,()->extraction.get(bob,job.id())).status());
    }
    private com.agentplatform.domain.chat.ChatRepository chat(){
        var repository=com.agentplatform.app.chat.ChatPersistenceFixture.create(pool,false,com.agentplatform.domain.chat.ContextPolicy.current(262144,4096));
        var cipher=new com.agentplatform.infrastructure.chat.CredentialCipher(new byte[32]);
        UUID credential=repository.importCredential(cipher.encrypt(UUID.randomUUID(),"PLATFORM",null,null,"fixture-not-a-provider-key"));
        repository.bind(UUID.fromString("0199e000-0000-7000-8000-000000000002"),new com.agentplatform.domain.chat.ChatData.Actor(alice.userId(),alice.spaceId()),credential,"PLATFORM");
        return repository;
    }
    private com.agentplatform.domain.chat.ChatData.Lease lease(com.agentplatform.domain.chat.ChatRepository repository){
        var actor=new com.agentplatform.domain.chat.ChatData.Actor(alice.userId(),alice.spaceId());var conversation=repository.create(actor,UUID.randomUUID(),"large-context-accounting");
        var task=repository.submit(actor,conversation.id(),UUID.randomUUID(),UUID.fromString("0199e000-0000-7000-8000-000000000002"),"hello");
        var publication=repository.publications(UUID.randomUUID()).stream().filter(p->p.envelope().aggregateId().equals(task.id())).findFirst().orElseThrow();
        var lease=repository.claim(publication.envelope(),UUID.randomUUID());assertNotNull(lease);repository.executionStartup(lease);return lease;
    }
    @Test void actualRequestEstimateRatherThanWholeWindowIsReservedAndLargeUsageSettles()throws Exception {
        var repository=chat();var lease=lease(repository);UUID turn=repository.beginTurn(lease);
        var call=repository.beginCall(lease,false,100000);repository.startCall(lease,call,false);
        assertEquals(109096,count("SELECT tokens FROM pf.budget_reservation WHERE call_id='"+call.id()+"'"));
        var round=new com.agentplatform.types.harness.HarnessData.Round("done",List.of(),"stop",90000,100);
        assertTrue(repository.commitRoundOutcome(lease,turn,new com.agentplatform.types.harness.HarnessData.Turn(turn.toString(),1,round),new com.agentplatform.domain.chat.ExecutionLedger.CallResult(call,true,"fixture-request",false)));
        assertEquals(90000,count("SELECT input_tokens FROM pf.model_call WHERE id='"+call.id()+"'"));
        assertEquals(0,count("SELECT count(*) FROM pf.budget_reservation WHERE call_id='"+call.id()+"' AND state='RESERVED'"));
        assertEquals(90100,count("SELECT consumed FROM pf.quota_bucket WHERE user_id='"+alice.userId()+"' AND metric='TOKENS'"));
    }
    @Test void largePrivateStateAndImmutableCheckpointUseTheFrozenProductionLimit()throws Exception {
        var repository=chat();var lease=lease(repository);
        var input=repository.executionStartup(lease).history();
        byte[] state=("{\"fixture\":\""+"x".repeat(1_100_000)+"\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(1,repository.stateCas(lease,"large-private-state",0,state));
        var codec=new com.fasterxml.jackson.databind.ObjectMapper();
        assertEquals("x".repeat(1_100_000),codec.readTree(repository.state(lease,"large-private-state").body()).path("fixture").asText());
        var draft=new com.agentplatform.types.harness.HarnessData.CheckpointDraft(0,state,input,com.agentplatform.types.harness.HarnessData.CheckpointPhase.INPUT_READY);
        var bundle=com.agentplatform.domain.chat.ChatRuntime.contextBundle(false);
        UUID checkpoint=repository.commitCheckpoint(lease,bundle,draft);
        repository.validateCheckpoint(lease,checkpoint,bundle);
        assertEquals(1,count("SELECT count(*) FROM pf.checkpoint WHERE id='"+checkpoint+"' AND octet_length(state_json::text)>1048576"));
        assertThrows(com.agentplatform.domain.chat.ChatFailure.class,()->repository.stateCas(lease,"large-private-state",0,state));
        assertEquals("x".repeat(1_100_000),codec.readTree(repository.state(lease,"large-private-state").body()).path("fixture").asText());
    }
    @Test void submissionSharesTheTaskTransactionAndSerializesConcurrentRequestIdentity()throws Exception {
        var repository=chat();var tx=new TransactionTemplate(new JdbcTransactionManager(pool));
        var coordinator=new AttachmentSubmission(session(pool).getMapper(TaskAttachmentMapper.class),tx,org.mockito.Mockito.mock(AttachmentAdmission.class));
        var actor=new com.agentplatform.domain.chat.ChatData.Actor(alice.userId(),alice.spaceId());
        UUID model=UUID.fromString("0199e000-0000-7000-8000-000000000002"),failed=UUID.randomUUID();
        assertThrows(IllegalStateException.class,()->coordinator.submit(alice,failed,"/conversations","frozen","hello",null,false,false,List.of(),()->{
            repository.first(actor,failed,"rollback",model,"PLATFORM","hello");throw new IllegalStateException("fixture-before-binding");
        },value->UUID.randomUUID()));
        assertEquals(0,count("SELECT count(*) FROM pf.task"));assertEquals(0,count("SELECT count(*) FROM pf.conversation"));
        UUID request=UUID.randomUUID();var start=new java.util.concurrent.CountDownLatch(1);
        try(var threads=java.util.concurrent.Executors.newFixedThreadPool(2)){
            java.util.concurrent.Callable<UUID> submit=()->{start.await();return coordinator.submit(alice,request,"/conversations","frozen","hello",null,false,false,List.of(),
                ()->repository.first(actor,request,"one request",model,"PLATFORM","hello"),value->value.task().id()).task().id();};
            var first=threads.submit(submit);var second=threads.submit(submit);start.countDown();
            assertEquals(first.get(15,java.util.concurrent.TimeUnit.SECONDS),second.get(15,java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(1,count("SELECT count(*) FROM pf.task"));assertEquals(1,count("SELECT count(*) FROM pf.attachment_submission"));
        assertThrows(com.agentplatform.domain.chat.ChatFailure.class,()->coordinator.submit(alice,request,"/conversations","changed","hello",null,false,false,List.of(),
                ()->repository.first(actor,request,"one request",model,"PLATFORM","hello"),value->value.task().id()));
    }
    @Test void underestimatedUsageSurvivesTheTransactionAndKeepsItsOriginalReservation()throws Exception {
        var repository=chat();var lease=lease(repository);UUID turn=repository.beginTurn(lease);
        var call=repository.beginCall(lease,false,1000);repository.startCall(lease,call,false);
        var round=new com.agentplatform.types.harness.HarnessData.Round("done",List.of(),"stop",20000,100);
        assertFalse(repository.commitRoundOutcome(lease,turn,new com.agentplatform.types.harness.HarnessData.Turn(turn.toString(),1,round),new com.agentplatform.domain.chat.ExecutionLedger.CallResult(call,true,"observed-request",false)));
        assertEquals(20000,count("SELECT input_tokens FROM pf.observed_usage WHERE call_id='"+call.id()+"'"));
        assertEquals(1,count("SELECT count(*) FROM pf.budget_reservation WHERE call_id='"+call.id()+"' AND state='RECONCILING'"));
        assertTrue(count("SELECT reserved FROM pf.quota_bucket WHERE user_id='"+alice.userId()+"' AND metric='TOKENS'")>0);
        assertEquals(0,count("SELECT count(*) FROM pf.model_turn WHERE task_id='"+lease.taskId()+"' AND status='COMMITTED'"));
    }
}
