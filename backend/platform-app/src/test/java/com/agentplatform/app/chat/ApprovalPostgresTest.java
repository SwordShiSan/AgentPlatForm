package com.agentplatform.app.chat;

import com.agentplatform.domain.chat.*;
import com.agentplatform.domain.chat.ChatData.*;
import com.agentplatform.domain.harness.HarnessPorts.ToolExecutionPort;
import com.agentplatform.infrastructure.chat.CredentialCipher;
import com.agentplatform.types.PlanInteractionData.*;
import com.agentplatform.types.TaskPermissionData.Mode;
import com.agentplatform.types.harness.HarnessData;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real isolated PG/MQ with the real SDK. No supplier or production tool is used. */
class ApprovalPostgresTest {
    static String url,password;
    static final UUID MODEL=UUID.fromString("0199e000-0000-7000-8000-000000000002");
    com.zaxxer.hikari.HikariDataSource pool;
    ChatRepository repository;
    com.agentplatform.domain.identity.IdentityStore identity;
    Actor user,other;
    static Actor administratorFixture;
    CredentialCipher cipher=new CredentialCipher(new byte[32]);
    @TempDir Path work;
    @BeforeAll static void migrate() throws Exception {
        url=System.getenv("R43_TEST_JDBC_URL");Assumptions.assumeTrue(url!=null,"R43 database not configured; NOT_RUN");
        assertEquals("jdbc:postgresql://127.0.0.1:15456/r43_test?connectTimeout=2&socketTimeout=5",url);
        password=Files.readString(Path.of(System.getenv("R43_TEST_PASSWORD_FILE"))).strip();
        Flyway.configure().dataSource(url,"postgres",password).defaultSchema("pf").schemas("pf","pf_runtime").cleanDisabled(false).load().clean();
        administratorFixture=null;
        var previous=Flyway.configure().dataSource(url,"postgres",password).defaultSchema("pf").schemas("pf","pf_runtime").target("16").load();previous.migrate();
        assertEquals("16",previous.info().current().getVersion().toString());
        var current=Flyway.configure().dataSource(url,"postgres",password).defaultSchema("pf").schemas("pf","pf_runtime").load();current.migrate();current.validate();
        assertEquals("17",current.info().current().getVersion().toString());
    }
    @BeforeEach void setup(){
        var config=new com.zaxxer.hikari.HikariConfig();config.setJdbcUrl(url);config.setUsername("postgres");config.setPassword(password);config.setMaximumPoolSize(4);config.setMinimumIdle(1);
        pool=new com.zaxxer.hikari.HikariDataSource(config);repository=ChatPersistenceFixture.create(pool);identity=com.agentplatform.app.identity.IdentityPersistenceFixture.create(pool);
        user=actor(false);other=actor(true);
        UUID key=repository.importCredential(cipher.encrypt(UUID.randomUUID(),"PLATFORM",null,null,"R43_FIXTURE_NOT_A_SUPPLIER_SECRET"));
        repository.bind(MODEL,user,key,"PLATFORM");repository.bind(MODEL,other,key,"PLATFORM");
    }
    @AfterEach void close(){if(pool!=null)pool.close();}
    Actor actor(boolean admin){
        if(admin&&administratorFixture!=null)return administratorFixture;
        String name="r43-"+UUID.randomUUID();UUID id=identity.provision(UUID.randomUUID(),name,name,"unused-fixture-password-hash",admin);
        var actor=new Actor(id,identity.spaces(id).getFirst().id());if(admin)administratorFixture=actor;return actor;
    }
    void sql(String query)throws Exception{try(var c=DriverManager.getConnection(url,"postgres",password);var s=c.createStatement()){s.execute(query);}}
    String scalar(String query)throws Exception{try(var c=DriverManager.getConnection(url,"postgres",password);var s=c.createStatement();var r=s.executeQuery(query)){assertTrue(r.next());return r.getString(1);}}
    long count(String query)throws Exception{return Long.parseLong(scalar(query));}
    Task task(){return repository.firstAuthorized(user,UUID.randomUUID(),"R43 approval",MODEL,"PLATFORM","Protected deterministic approval",false,null,Mode.RISK_BASED).task();}
    Lease claim(Task task){UUID relay=UUID.randomUUID();var message=repository.publications(relay).stream().filter(p->p.envelope().aggregateId().equals(task.id())).findFirst().orElseThrow();repository.published(relay,message,true);var lease=repository.claim(message.envelope(),UUID.randomUUID());assertNotNull(lease);return lease;}
    HarnessData.Call invocation(String id){return new HarnessData.Call(id,"approval_probe",Map.of("text","same protected effect"),false);}
    HarnessData.Round tool(String id){return new HarnessData.Round("",List.of(invocation(id)),"tool_calls",12,4);}
    HarnessData.Round done(){return new HarnessData.Round("Approval handling completed 666",List.of(),"stop",12,4);}
    ChatProvider provider(AtomicInteger calls,List<HarnessData.Round> rounds){return new ChatProvider(){
        public boolean real(){return false;}
        public CompletionStage<Result> stream(String key,List<HarnessData.Message> input,java.util.function.Consumer<String> text){throw new AssertionError("Typed SDK path required");}
        public Exchange openTyped(String model,String key,List<HarnessData.Message> input,List<HarnessData.ToolDefinition> definitions,java.util.function.Consumer<String> text){
            assertTrue(definitions.stream().anyMatch(d->d.name().equals("approval_probe")));int index=calls.getAndIncrement();assertTrue(index<rounds.size(),"Unexpected model replay");var round=rounds.get(index);
            if(!round.text().isEmpty())text.accept(round.text());var result=CompletableFuture.completedFuture(new Result(round,true,"r43-fixture-"+index));
            return new Exchange(){public CompletionStage<Result> result(){return result;}public CompletionStage<Void> terminated(){return CompletableFuture.completedFuture(null);}public void cancel(){}};
        }
    };}
    void run(Lease lease,ChatProvider provider,AtomicInteger effects){run(lease,provider,(scope,call)->{effects.incrementAndGet();return CompletableFuture.completedFuture(new HarnessData.Observation(call.id(),call.name(),"Protected effect 666",HarnessData.OperationStatus.SUCCEEDED));});}
    void run(Lease lease,ChatProvider provider,ToolExecutionPort tools){
        var timers=Executors.newScheduledThreadPool(2);
        try(var execution=new ChatExecution(repository,provider,cipher,work,timers,tools)){execution.configureToolApprovals();execution.run(lease);}finally{timers.shutdownNow();}
    }
    Pending waiting(Task task,ChatProvider provider,AtomicInteger effects){run(claim(task),provider,effects);assertEquals("WAITING_INPUT",repository.task(user,task.id()).status());var pending=repository.pending(user,task.conversationId()).getFirst();assertEquals(Kind.TOOL_APPROVAL,pending.kind());assertNotNull(pending.approval());return pending;}
    Accepted answer(Task task,Pending pending,UUID key,Decision decision){return repository.answer(user,task.id(),pending.interactionId(),key,new Answer(pending.version(),decision,"",List.of()));}

    @Test void allowRestoresOriginalOperationAndConsumesExactlyOnce()throws Exception{
        var task=task();var calls=new AtomicInteger();var effects=new AtomicInteger();var provider=provider(calls,List.of(tool("original-call"),done()));
        var pending=waiting(task,provider,effects);assertEquals(0,effects.get());assertEquals(1,calls.get());
        String operation=pending.approval().operationId().toString();String facts=scalar("SELECT canonical_hash FROM pf.checkpoint WHERE task_id='"+task.id()+"' AND phase='WAITING_APPROVAL'");
        assertEquals(0,count("SELECT count(*) FROM pf.execution_slot WHERE task_id IS NOT NULL"));
        assertEquals("CONVERSATION_WAITING_INPUT",assertThrows(ChatFailure.class,()->repository.submitPlan(user,task.conversationId(),UUID.randomUUID(),MODEL,"PLATFORM","no interruption",false)).code());
        UUID key=UUID.randomUUID();var accepted=answer(task,pending,key,Decision.ALLOW_ONCE);assertEquals(accepted,answer(task,pending,key,Decision.ALLOW_ONCE));
        assertEquals(accepted,answer(task,pending,UUID.randomUUID(),Decision.ALLOW_ONCE));
        assertEquals("PREPARED",scalar("SELECT status FROM pf.tool_operation WHERE id='"+operation+"'"));
        assertEquals(facts,scalar("SELECT canonical_hash FROM pf.checkpoint WHERE task_id='"+task.id()+"' AND phase='WAITING_APPROVAL'"));
        assertEquals(409,assertThrows(ChatFailure.class,()->answer(task,pending,UUID.randomUUID(),Decision.DENY)).status());
        run(claim(task),provider,effects);
        assertEquals("SUCCEEDED",repository.task(user,task.id()).status());assertEquals(1,effects.get());assertEquals(2,calls.get());
        assertEquals(1,count("SELECT count(*) FROM pf.tool_operation WHERE task_id='"+task.id()+"'"));
        assertEquals(1,count("SELECT count(*) FROM pf.tool_approval_consumption WHERE operation_id='"+operation+"' AND outcome='ALLOW_ONCE'"));
        assertEquals(1,count("SELECT count(*) FROM pf.interaction_answer WHERE task_id='"+task.id()+"'"));
        assertEquals(1,count("SELECT count(*) FROM pf.message WHERE task_id='"+task.id()+"' AND role='USER'"));
    }
    @Test void denialPersistsBeforeNextModelAndSuppressesSameFingerprint()throws Exception{
        var task=task();var calls=new AtomicInteger();var effects=new AtomicInteger();var provider=provider(calls,List.of(tool("deny-first"),tool("deny-new-id"),done()));
        var pending=waiting(task,provider,effects);answer(task,pending,UUID.randomUUID(),Decision.DENY);run(claim(task),provider,effects);
        assertEquals("SUCCEEDED",repository.task(user,task.id()).status());assertEquals(0,effects.get());assertEquals(3,calls.get());
        assertEquals(2,count("SELECT count(*) FROM pf.tool_operation WHERE task_id='"+task.id()+"' AND status='DENIED' AND result_json->>'status'='DENIED'"));
        assertEquals(1,count("SELECT count(*) FROM pf.task_interaction WHERE task_id='"+task.id()+"' AND kind='TOOL_APPROVAL'"));
        assertTrue(scalar("SELECT canonical_json::text FROM pf.checkpoint WHERE task_id='"+task.id()+"' AND phase='TERMINAL' ORDER BY created_at DESC LIMIT 1").contains("DENIED"));
    }
    @Test void permissionChangeStalesApprovalButStillAllowsDenial()throws Exception{
        var task=task();var calls=new AtomicInteger();var effects=new AtomicInteger();var provider=provider(calls,List.of(tool("stale"),done()));var pending=waiting(task,provider,effects);
        assertEquals(404,assertThrows(ChatFailure.class,()->repository.pending(other,task.conversationId())).status());
        repository.permissionMode(user,task.id(),UUID.randomUUID(),1,Mode.FULL);
        var updated=repository.pending(user,task.conversationId()).getFirst();assertTrue(updated.approval().stale());assertEquals(List.of(Decision.DENY),updated.approval().allowedActions());
        assertEquals(412,assertThrows(ChatFailure.class,()->answer(task,pending,UUID.randomUUID(),Decision.ALLOW_ONCE)).status());
        answer(task,pending,UUID.randomUUID(),Decision.DENY);run(claim(task),provider,effects);assertEquals(0,effects.get());assertEquals("SUCCEEDED",repository.task(user,task.id()).status());
    }
    @Test void approvedThenChangedRevisionCannotExecute()throws Exception{
        var task=task();var calls=new AtomicInteger();var effects=new AtomicInteger();var provider=provider(calls,List.of(tool("changed-after-answer"),done()));var pending=waiting(task,provider,effects);
        answer(task,pending,UUID.randomUUID(),Decision.ALLOW_ONCE);repository.permissionMode(user,task.id(),UUID.randomUUID(),1,Mode.FULL);run(claim(task),provider,effects);
        assertEquals(0,effects.get());assertEquals("SUCCEEDED",repository.task(user,task.id()).status());
        assertEquals("STALE_DENIED",scalar("SELECT outcome FROM pf.tool_approval_consumption WHERE task_id='"+task.id()+"'"));
    }
    @Test void concurrentSameDecisionCreatesOneAnswerAndOneResume()throws Exception{
        var task=task();var calls=new AtomicInteger();var effects=new AtomicInteger();var provider=provider(calls,List.of(tool("race"),done()));var pending=waiting(task,provider,effects);
        var start=new CountDownLatch(1);Runnable action=()->{try{assertTrue(start.await(5,TimeUnit.SECONDS));answer(task,pending,UUID.randomUUID(),Decision.ALLOW_ONCE);}catch(InterruptedException failure){throw new RuntimeException(failure);}};
        var a=CompletableFuture.runAsync(action);var b=CompletableFuture.runAsync(action);start.countDown();CompletableFuture.allOf(a,b).get(15,TimeUnit.SECONDS);
        assertEquals(1,count("SELECT count(*) FROM pf.interaction_answer WHERE task_id='"+task.id()+"'"));assertEquals(1,count("SELECT count(*) FROM pf.interaction_resume WHERE task_id='"+task.id()+"'"));
        run(claim(task),provider,effects);assertEquals(1,effects.get());
    }
    @Test void forgedCallAndCheckThenPermissionChangeCannotConsume()throws Exception{
        var task=task();var calls=new AtomicInteger();var effects=new AtomicInteger();var provider=provider(calls,List.of(tool("exact"),done()));var pending=waiting(task,provider,effects);
        answer(task,pending,UUID.randomUUID(),Decision.ALLOW_ONCE);var lease=claim(task);repository.executionStartup(lease);
        assertEquals(HarnessData.Decision.ALLOW,repository.operationDecision(lease,invocation("exact")));
        var forged=new HarnessData.Call("exact","approval_probe",Map.of("text","changed"),false);assertThrows(ChatFailure.class,()->repository.startTool(lease,forged));
        repository.permissionMode(user,task.id(),UUID.randomUUID(),1,Mode.FULL);assertThrows(ChatFailure.class,()->repository.startTool(lease,invocation("exact")));
        assertEquals(0,count("SELECT count(*) FROM pf.tool_approval_consumption WHERE task_id='"+task.id()+"'"));
        repository.stopped(lease,"FAILED","FIXTURE_COMPLETE",null,"b".repeat(64));assertThrows(ChatFailure.class,()->repository.startTool(lease,invocation("exact")));
    }
    @Test void uncertainStartedToolIsNeverReplayed()throws Exception{
        var task=task();var calls=new AtomicInteger();var effects=new AtomicInteger();var provider=provider(calls,List.of(tool("unknown"),done()));var pending=waiting(task,provider,effects);
        answer(task,pending,UUID.randomUUID(),Decision.ALLOW_ONCE);run(claim(task),provider,(scope,call)->{effects.incrementAndGet();return CompletableFuture.failedFuture(new IllegalStateException("Fixture lost result after effect"));});
        assertEquals(1,effects.get());assertEquals(1,calls.get());assertEquals("BLOCKED",repository.task(user,task.id()).status());
        assertEquals("UNKNOWN",scalar("SELECT status FROM pf.tool_operation WHERE task_id='"+task.id()+"'"));assertFalse(repository.recovery(user,task.id()).eligible());
        assertEquals(409,assertThrows(ChatFailure.class,()->answer(task,pending,UUID.randomUUID(),Decision.DENY)).status());
    }
    @Test void expiryAndCancelNeverGrantExecution()throws Exception{
        var task=task();var calls=new AtomicInteger();var effects=new AtomicInteger();var provider=provider(calls,List.of(tool("expires"),done()));var pending=waiting(task,provider,effects);
        sql("UPDATE pf.task_interaction SET expires_at=clock_timestamp()-interval '1 second' WHERE id='"+pending.interactionId()+"'");
        assertEquals(409,assertThrows(ChatFailure.class,()->answer(task,pending,UUID.randomUUID(),Decision.ALLOW_ONCE)).status());
        repository.cancel(user,task.id(),UUID.randomUUID());assertEquals("CANCELLED",repository.task(user,task.id()).status());assertEquals(0,effects.get());
    }
    @Test void expiryBeforeTransactionCommitRollsBackConsumptionAndRunningState()throws Exception{
        var task=task();var calls=new AtomicInteger();var effects=new AtomicInteger();var provider=provider(calls,List.of(tool("commit-expiry"),done()));var pending=waiting(task,provider,effects);
        answer(task,pending,UUID.randomUUID(),Decision.ALLOW_ONCE);var lease=claim(task);repository.executionStartup(lease);
        sql("CREATE FUNCTION pf.r43_slow_approval_consume() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.task_id='"+task.id()+"'::uuid THEN PERFORM pg_sleep(1.2); END IF; RETURN NEW; END $$;"
                +"CREATE TRIGGER r43_slow_approval_consume BEFORE INSERT ON pf.tool_approval_consumption FOR EACH ROW EXECUTE FUNCTION pf.r43_slow_approval_consume();"
                +"UPDATE pf.task_interaction SET expires_at=clock_timestamp()+interval '1 second' WHERE id='"+pending.interactionId()+"'");
        try{
            var refused=assertThrows(ChatFailure.class,()->repository.startTool(lease,invocation("commit-expiry")));assertEquals("APPROVAL_EXPIRED",refused.code());
            assertEquals(0,count("SELECT count(*) FROM pf.tool_approval_consumption WHERE task_id='"+task.id()+"'"));
            assertEquals("PREPARED",scalar("SELECT status FROM pf.tool_operation WHERE task_id='"+task.id()+"'"));assertEquals(0,effects.get());
        }finally{sql("DROP TRIGGER r43_slow_approval_consume ON pf.tool_approval_consumption;DROP FUNCTION pf.r43_slow_approval_consume()");repository.stopped(lease,"FAILED","FIXTURE_COMPLETE",null,"c".repeat(64));}
    }
    @Test void unsupportedBatchIsFullyRecordedButNeverExecuted()throws Exception{
        var task=task();var calls=new AtomicInteger();var effects=new AtomicInteger();var batch=new HarnessData.Round("",List.of(invocation("one"),invocation("two")),"tool_calls",12,4);
        run(claim(task),provider(calls,List.of(batch)),effects);assertEquals(0,effects.get());assertEquals(2,count("SELECT count(*) FROM pf.tool_operation WHERE task_id='"+task.id()+"'"));
        assertTrue(repository.pending(user,task.conversationId()).isEmpty());assertEquals("FAILED",repository.task(user,task.id()).status());assertEquals("TOOL_BATCH_UNSUPPORTED",repository.task(user,task.id()).reasonCode());
    }
    @Test void confirmAndActualAckLossDoNotCreateASecondOwnerOrEffect()throws Exception{
        String file=System.getenv("R43_MQ_PASSWORD_FILE");Assumptions.assumeTrue(file!=null,"Isolated R43 broker not configured; NOT_RUN");
        var task=task();var calls=new AtomicInteger();var effects=new AtomicInteger();var provider=provider(calls,List.of(tool("mq-once"),done()));var pending=waiting(task,provider,effects);
        answer(task,pending,UUID.randomUUID(),Decision.ALLOW_ONCE);
        var broker=new com.rabbitmq.client.ConnectionFactory();broker.setHost("127.0.0.1");broker.setPort(15714);broker.setVirtualHost("/agentplatform");broker.setUsername("ap_operator");broker.setPassword(Files.readString(Path.of(file)).strip());broker.setAutomaticRecoveryEnabled(false);broker.setConnectionTimeout(2000);
        var managed=new org.springframework.amqp.rabbit.connection.CachingConnectionFactory(broker);
        try(var connection=broker.newConnection();var transport=new com.agentplatform.infrastructure.chat.SpringAmqpDispatchTransport(managed,repository)){
            var channel=connection.createChannel();UUID relay=UUID.randomUUID();var publication=repository.publications(relay).stream().filter(p->p.envelope().aggregateId().equals(task.id())).findFirst().orElseThrow();
            transport.publish(publication.envelope());repository.published(relay,publication,false);var first=received(channel);var envelope=tools.jackson.databind.json.JsonMapper.builder().build().readValue(first.getBody(),Envelope.class);
            var target=repository.claim(envelope,UUID.randomUUID());assertNotNull(target);channel.abort();
            try(var second=connection.createChannel()){
                var redelivery=received(second);assertTrue(redelivery.getEnvelope().isRedeliver());assertNull(repository.claim(envelope,UUID.randomUUID()));second.basicAck(redelivery.getEnvelope().getDeliveryTag(),false);
                transport.publish(envelope);var duplicate=received(second);assertNull(repository.claim(envelope,UUID.randomUUID()));second.basicAck(duplicate.getEnvelope().getDeliveryTag(),false);
            }
            run(target,provider,effects);assertEquals(1,effects.get());assertEquals(2,calls.get());assertEquals(1,count("SELECT count(*) FROM pf.tool_approval_consumption WHERE task_id='"+task.id()+"'"));
        }finally{managed.destroy();}
    }
    private com.rabbitmq.client.GetResponse received(com.rabbitmq.client.Channel channel)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<deadline){var value=channel.basicGet("ap.execution.v1",false);if(value!=null)return value;Thread.sleep(20);}throw new AssertionError("Expected actual Rabbit delivery");
    }
}
