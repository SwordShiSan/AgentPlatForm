package com.agentplatform.app.chat;

import com.agentplatform.domain.chat.*;
import com.agentplatform.domain.chat.ChatData.*;
import com.agentplatform.types.PlanInteractionData.*;
import com.agentplatform.types.harness.HarnessData;
import com.agentplatform.infrastructure.chat.CredentialCipher;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Exact isolated R4.1 database only. Supplier calls are always zero. */
class PlanQuotaPostgresTest {
    static String url,password;
    static final UUID MODEL=UUID.fromString("0199e000-0000-7000-8000-000000000002");
    com.zaxxer.hikari.HikariDataSource pool;
    ChatRepository repository;
    com.agentplatform.domain.identity.IdentityStore identity;
    Actor user,admin;
    static Actor administratorFixture;
    CredentialCipher cipher=new CredentialCipher(new byte[32]);
    @TempDir Path work;
    @BeforeAll static void migrate() throws Exception {
        url=System.getenv("R41_TEST_JDBC_URL");Assumptions.assumeTrue(url!=null,"R41 database not configured; NOT_RUN");
        assertEquals("jdbc:postgresql://127.0.0.1:15454/r41_test?connectTimeout=2&socketTimeout=5",url);
        password=Files.readString(Path.of(System.getenv("R41_TEST_PASSWORD_FILE"))).strip();
        Flyway.configure().dataSource(url,"postgres",password).defaultSchema("pf").schemas("pf","pf_runtime").cleanDisabled(false).load().clean();
        administratorFixture=null;
        var before=Flyway.configure().dataSource(url,"postgres",password).defaultSchema("pf").schemas("pf","pf_runtime").target("14").load();before.migrate();
        assertEquals("14",before.info().current().getVersion().toString());
        var current=Flyway.configure().dataSource(url,"postgres",password).defaultSchema("pf").schemas("pf","pf_runtime").load();current.migrate();
        assertEquals("15",current.info().current().getVersion().toString());current.validate();
    }
    @BeforeEach void setup() {
        var configuration=new com.zaxxer.hikari.HikariConfig();configuration.setJdbcUrl(url);configuration.setUsername("postgres");configuration.setPassword(password);configuration.setMaximumPoolSize(4);configuration.setMinimumIdle(1);
        pool=new com.zaxxer.hikari.HikariDataSource(configuration);repository=ChatPersistenceFixture.create(pool);identity=com.agentplatform.app.identity.IdentityPersistenceFixture.create(pool);
        user=actor(false);admin=actor(true);
        var key=repository.importCredential(cipher.encrypt(UUID.randomUUID(),"PLATFORM",null,null,"fixture-not-a-real-supplier-key"));
        repository.bind(MODEL,user,key,"PLATFORM");repository.bind(MODEL,admin,key,"PLATFORM");
    }
    @AfterEach void close(){if(pool!=null)pool.close();}
    Actor actor(boolean administrator){
        if(administrator&&administratorFixture!=null)return administratorFixture;
        String name="r41-"+UUID.randomUUID();UUID id=identity.provision(UUID.randomUUID(),name,name,"unused-test-password-hash",administrator);
        var result=new Actor(id,identity.spaces(id).getFirst().id());if(administrator)administratorFixture=result;return result;
    }
    Task task(Actor actor,boolean plan){return repository.firstPlan(actor,UUID.randomUUID(),"R41",MODEL,"PLATFORM","请规划并处理文字",plan).task();}
    Lease claim(Task task){UUID relay=UUID.randomUUID();var published=repository.publications(relay).stream().filter(x->x.envelope().aggregateId().equals(task.id())).findFirst().orElseThrow();repository.published(relay,published,true);return Objects.requireNonNull(repository.claim(published.envelope(),UUID.randomUUID()));}
    void sql(String sql) throws Exception {try(var connection=DriverManager.getConnection(url,"postgres",password);var statement=connection.createStatement()){statement.execute(sql);}}
    long count(String sql) throws Exception {try(var connection=DriverManager.getConnection(url,"postgres",password);var statement=connection.createStatement();var result=statement.executeQuery(sql)){assertTrue(result.next());return result.getLong(1);}}
    @Test void ordinaryDailyQuotaIsConfigurableAndAdministratorIsUnlimitedButMetered() throws Exception {
        assertEquals(1000000L,repository.quota(user).stream().filter(q->q.metric()==QuotaData.Metric.TOKENS).findFirst().orElseThrow().limit().longValue());
        assertTrue(repository.quota(admin).stream().allMatch(QuotaData.View::unlimited));
        sql("INSERT INTO pf.quota_policy(id,subject_user_id,metric,limit_value) VALUES(gen_random_uuid(),'"+user.userId()+"','TOKENS',9226)");
        var task=task(user,false);var lease=claim(task);repository.executionStartup(lease);
        var first=repository.beginCall(lease,false);repository.startCall(lease,first,false);
        assertEquals("DAILY_TOKEN_QUOTA_EXCEEDED",assertThrows(ChatFailure.class,()->repository.beginCall(lease,false)).code());
        repository.settle(lease,first,new HarnessData.Round("ok",List.of(),"stop",8,2),"fixture",null,false);
        var second=repository.beginCall(lease,false);repository.settle(lease,second,null,null,"CALL_NOT_SENT",false);
        assertEquals(10,repository.quota(user).stream().filter(q->q.metric()==QuotaData.Metric.TOKENS).findFirst().orElseThrow().used());
        assertEquals(0,repository.quota(user).stream().filter(q->q.metric()==QuotaData.Metric.TOKENS).findFirst().orElseThrow().reserved());
        repository.stopped(lease,"FAILED","FIXTURE_DONE",null,"a".repeat(64));
    }
    @Test void fullPlanWaitEditAndAnswerResumeSameTaskAndRejectStaleOrOtherUser() throws Exception {
        var task=task(user,true);var calls=new AtomicInteger();var provider=provider(calls);
        try(var execution=new ChatExecution(repository,provider,cipher,work)) {execution.run(claim(task));}
        assertEquals(2,calls.get());assertEquals("WAITING_INPUT",repository.task(user,task.id()).status());
        assertEquals(0,count("SELECT count(*) FROM pf.execution_slot WHERE task_id IS NOT NULL"));
        var pending=repository.pending(user,task.conversationId()).getFirst();
        assertEquals(Kind.PLAN_APPROVAL,pending.kind());assertNotNull(pending.plan());
        assertThrows(ChatFailure.class,()->repository.plan(admin,task.id()));
        assertEquals("CONVERSATION_WAITING_INPUT",assertThrows(ChatFailure.class,()->repository.submitPlan(user,task.conversationId(),UUID.randomUUID(),MODEL,"PLATFORM","不能插队",false)).code());
        var plan=repository.plan(user,task.id());repository.editPlan(user,task.id(),plan.version(),new Edit("修订计划","按新修订处理文本666"));
        assertEquals("INTERACTION_VERSION_CONFLICT",assertThrows(ChatFailure.class,()->repository.answer(user,task.id(),pending.interactionId(),UUID.randomUUID(),new Answer(pending.version(),Decision.EXECUTE_PLAN,"",List.of()))).code());
        var current=repository.pending(user,task.conversationId()).getFirst();UUID key=UUID.randomUUID();var answer=new Answer(current.version(),Decision.EXECUTE_PLAN,"",List.of());
        var accepted=repository.answer(user,task.id(),current.interactionId(),key,answer);
        assertEquals("QUEUED",accepted.status());assertEquals(accepted,repository.answer(user,task.id(),current.interactionId(),key,answer));
        var next=claim(task);assertEquals(2,count("SELECT count(*) FROM pf.attempt WHERE task_id='"+task.id()+"'"));
        try(var execution=new ChatExecution(repository,provider,cipher,work)) {execution.run(next);}
        assertEquals(3,calls.get());assertEquals("SUCCEEDED",repository.task(user,task.id()).status());
        assertEquals(2,repository.plan(user,task.id()).revisions().size());
        assertEquals(1,count("SELECT count(*) FROM pf.message WHERE task_id='"+task.id()+"' AND role='USER'"));
        assertTrue(repository.snapshot(user,task.conversationId()).pending().isEmpty());
    }
    @Test void deliverPlanCompletesWithoutAnotherProviderCall() throws Exception {
        var task=task(user,true);var calls=new AtomicInteger();
        try(var execution=new ChatExecution(repository,provider(calls),cipher,work)){execution.run(claim(task));}
        var pending=repository.pending(user,task.conversationId()).getFirst();
        repository.answer(user,task.id(),pending.interactionId(),UUID.randomUUID(),new Answer(pending.version(),Decision.DELIVER_PLAN,"",List.of()));
        assertEquals("SUCCEEDED",repository.task(user,task.id()).status());assertEquals(2,calls.get());
        assertEquals("DELIVERED",repository.plan(user,task.id()).status());
    }
    @Test void requiredQuestionResumesOnlyOnceAndThenOpensAPlanReview() throws Exception {
        var task=task(user,true);var calls=new AtomicInteger();
        var rounds=List.of(
                control("question-1","platform_ask_user",Map.of("question","希望怎样呈现？","optional",false,"options",List.of(Map.of("id","brief","label","简要")))),
                control("write-1","platform_plan_write",Map.of("title","已补充的计划","markdown","按用户选择提供简要文本。")),
                control("review-1","platform_plan_review",Map.of()));
        var provider=scripted(rounds,calls);
        try(var execution=new ChatExecution(repository,provider,cipher,work)){execution.run(claim(task));}
        assertEquals("WAITING_INPUT",repository.task(user,task.id()).status());var question=repository.pending(user,task.conversationId()).getFirst();assertEquals(Kind.QUESTION,question.kind());
        assertEquals("QUESTION_SKIP_NOT_ALLOWED",assertThrows(ChatFailure.class,()->repository.answer(user,task.id(),question.interactionId(),UUID.randomUUID(),new Answer(question.version(),Decision.SKIP,"",List.of()))).code());
        repository.answer(user,task.id(),question.interactionId(),UUID.randomUUID(),new Answer(question.version(),Decision.ANSWER,"保留中文😀",List.of("brief")));
        try(var execution=new ChatExecution(repository,provider,cipher,work)){execution.run(claim(task));}
        assertEquals(3,calls.get());assertEquals("WAITING_INPUT",repository.task(user,task.id()).status());
        assertEquals(Kind.PLAN_APPROVAL,repository.pending(user,task.conversationId()).getFirst().kind());
        assertEquals(1,count("SELECT count(*) FROM pf.interaction_resume WHERE task_id='"+task.id()+"'"));
        repository.cancel(user,task.id(),UUID.randomUUID());assertEquals("CANCELLED",repository.task(user,task.id()).status());assertTrue(repository.pending(user,task.conversationId()).isEmpty());
    }
    @Test void lowUsageCanExceedFourPhysicalRequestsAndEightSdkRounds() throws Exception {
        var task=task(user,true);var rounds=new ArrayList<HarnessData.Round>();
        for(int i=0;i<9;i++)rounds.add(control("write-"+i,"platform_plan_write",Map.of("title","计划修订"+i,"markdown","小用量修订"+i)));
        rounds.add(control("review-9","platform_plan_review",Map.of()));var calls=new AtomicInteger();
        try(var execution=new ChatExecution(repository,scripted(rounds,calls),cipher,work)){execution.run(claim(task));}
        assertEquals(10,calls.get());assertEquals("WAITING_INPUT",repository.task(user,task.id()).status());assertEquals(9,repository.plan(user,task.id()).revisions().size());
        var tokens=repository.quota(user).stream().filter(q->q.metric()==QuotaData.Metric.TOKENS).findFirst().orElseThrow();assertEquals(140,tokens.used());assertEquals(0,tokens.reserved());
        assertEquals(0,count("SELECT reserved_tokens FROM pf.budget_account WHERE user_id='"+user.userId()+"'"));
        repository.cancel(user,task.id(),UUID.randomUUID());
    }
    @Test void expiryAndCancellationNeverGrantApprovalOrReplay() throws Exception {
        var task=task(user,true);var calls=new AtomicInteger();
        try(var execution=new ChatExecution(repository,provider(calls),cipher,work)){execution.run(claim(task));}
        var pending=repository.pending(user,task.conversationId()).getFirst();
        sql("UPDATE pf.task_interaction SET expires_at=clock_timestamp()-interval '1 second' WHERE id='"+pending.interactionId()+"'");
        repository.wakeDeferred();assertEquals("FAILED",repository.task(user,task.id()).status());assertEquals("INPUT_EXPIRED",repository.task(user,task.id()).reasonCode());
        assertTrue(repository.pending(user,task.conversationId()).isEmpty());assertEquals(2,calls.get());
        assertThrows(ChatFailure.class,()->repository.answer(user,task.id(),pending.interactionId(),UUID.randomUUID(),new Answer(pending.version(),Decision.EXECUTE_PLAN,"",List.of())));
    }
    @Test void quotaConfigurationUsesCasAndAdminRoleNotUsername() throws Exception {
        var command=new QuotaData.Change(UUID.randomUUID(),user.userId(),QuotaData.Metric.TOKENS,100L,"Asia/Shanghai",0,"fixture-operator");
        assertEquals(1,repository.configureQuota(command));assertEquals(1,repository.configureQuota(command));
        assertEquals(100,repository.quota(user).stream().filter(q->q.metric()==QuotaData.Metric.TOKENS).findFirst().orElseThrow().limit().longValue());
        assertEquals("QUOTA_POLICY_VERSION_CONFLICT",assertThrows(ChatFailure.class,()->repository.configureQuota(new QuotaData.Change(UUID.randomUUID(),user.userId(),QuotaData.Metric.TOKENS,200L,"Asia/Shanghai",0,"fixture-operator"))).code());
        var task=task(admin,false);var lease=claim(task);repository.executionStartup(lease);
        repository.configureQuota(new QuotaData.Change(UUID.randomUUID(),admin.userId(),QuotaData.Metric.TOKENS,0L,"Asia/Shanghai",0,"fixture-operator"));
        var call=repository.beginCall(lease,false);repository.startCall(lease,call,false);repository.settle(lease,call,new HarnessData.Round("ok",List.of(),"stop",5,2),"admin-fixture",null,false);
        var quota=repository.quota(admin).stream().filter(q->q.metric()==QuotaData.Metric.TOKENS).findFirst().orElseThrow();assertTrue(quota.unlimited());assertEquals(7,quota.used());
        sql("UPDATE pf.app_user SET platform_admin=false WHERE id='"+admin.userId()+"'");
        assertEquals("DAILY_TOKEN_QUOTA_EXCEEDED",assertThrows(ChatFailure.class,()->repository.beginCall(lease,false)).code());
        sql("UPDATE pf.app_user SET platform_admin=true WHERE id='"+admin.userId()+"'");
        repository.stopped(lease,"FAILED","FIXTURE_DONE",null,"b".repeat(64));
    }
    @Test void acceptedAnswerUsesRealRabbitConfirmAckAndDuplicateHandoffWithoutSecondOwner() throws Exception {
        String file=System.getenv("R41_MQ_PASSWORD_FILE");Assumptions.assumeTrue(file!=null,"Isolated R41 broker not configured; NOT_RUN");
        var task=task(user,true);var calls=new AtomicInteger();var provider=provider(calls);
        try(var execution=new ChatExecution(repository,provider,cipher,work)){execution.run(claim(task));}
        var pending=repository.pending(user,task.conversationId()).getFirst();
        repository.answer(user,task.id(),pending.interactionId(),UUID.randomUUID(),new Answer(pending.version(),Decision.EXECUTE_PLAN,"",List.of()));
        var broker=new com.rabbitmq.client.ConnectionFactory();broker.setHost("127.0.0.1");broker.setPort(15710);broker.setVirtualHost("/agentplatform");broker.setUsername("ap_operator");
        broker.setPassword(Files.readString(Path.of(file)).strip());broker.setAutomaticRecoveryEnabled(false);broker.setConnectionTimeout(2000);
        var managed=new org.springframework.amqp.rabbit.connection.CachingConnectionFactory(broker);
        try(var connection=broker.newConnection();var channel=connection.createChannel();
            var transport=new com.agentplatform.infrastructure.chat.SpringAmqpDispatchTransport(managed,repository)){
            UUID relay=UUID.randomUUID();var publication=repository.publications(relay).stream().filter(p->p.envelope().aggregateId().equals(task.id())).findFirst().orElseThrow();
            transport.publish(publication.envelope());repository.published(relay,publication,true);
            var delivery=received(channel);var envelope=tools.jackson.databind.json.JsonMapper.builder().build().readValue(delivery.getBody(),Envelope.class);
            assertEquals(publication.envelope(),envelope);var target=repository.claim(envelope,UUID.randomUUID());assertNotNull(target);
            channel.basicAck(delivery.getEnvelope().getDeliveryTag(),false);
            transport.publish(envelope);var duplicate=received(channel);assertNull(repository.claim(envelope,UUID.randomUUID()));channel.basicAck(duplicate.getEnvelope().getDeliveryTag(),false);
            try(var execution=new ChatExecution(repository,provider,cipher,work)){execution.run(target);}
            assertEquals("SUCCEEDED",repository.task(user,task.id()).status());assertEquals(3,calls.get());
            assertEquals(2,count("SELECT count(*) FROM pf.attempt WHERE task_id='"+task.id()+"'"));
        }finally{managed.destroy();}
    }
    private com.rabbitmq.client.GetResponse received(com.rabbitmq.client.Channel channel)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<deadline){var value=channel.basicGet("ap.execution.v1",false);if(value!=null)return value;Thread.sleep(20);}
        throw new AssertionError("Expected actual broker delivery");
    }
    @Test void unknownReservationSurvivesWindowChangeAndLateUsageSettlesOriginalBucketOnce() throws Exception {
        repository.configureQuota(new QuotaData.Change(UUID.randomUUID(),user.userId(),QuotaData.Metric.TOKENS,10000L,"Asia/Shanghai",0,"fixture"));
        var firstTask=task(user,false);var lease=claim(firstTask);repository.executionStartup(lease);var call=repository.beginCall(lease,false);repository.startCall(lease,call,false);
        repository.settle(lease,call,null,null,"PROVIDER_REQUEST_FAILED",false);
        sql("INSERT INTO pf.quota_bucket(user_id,metric,window_start,window_end) SELECT user_id,metric,window_start-interval '1 day',window_end-interval '1 day' FROM pf.quota_bucket WHERE user_id='"+user.userId()+"';"
                +"UPDATE pf.quota_reservation SET window_start=window_start-interval '1 day' WHERE call_id='"+call.id()+"';"
                +"UPDATE pf.quota_bucket SET reserved=CASE WHEN window_start=(date_trunc('day',clock_timestamp() AT TIME ZONE 'Asia/Shanghai') AT TIME ZONE 'Asia/Shanghai') THEN 0 ELSE CASE WHEN metric='TOKENS' THEN 9216 ELSE (SELECT amount_micros FROM pf.budget_reservation WHERE call_id='"+call.id()+"') END END WHERE user_id='"+user.userId()+"'");
        repository.stopped(lease,"BLOCKED","PROVIDER_REQUEST_FAILED",null,"c".repeat(64));
        assertEquals(9216,repository.quota(user).stream().filter(q->q.metric()==QuotaData.Metric.TOKENS).findFirst().orElseThrow().reserved());
        var secondTask=task(user,false);var target=claim(secondTask);repository.executionStartup(target);
        assertEquals("DAILY_TOKEN_QUOTA_EXCEEDED",assertThrows(ChatFailure.class,()->repository.beginCall(target,false)).code());
        repository.stopped(target,"FAILED","FIXTURE_DONE",null,"d".repeat(64));
        var command=new CancellationLedger.Command(call.id(),UUID.randomUUID(),0,CancellationLedger.Decision.CONFIRMED_USAGE,UUID.randomUUID(),"e".repeat(64),"PROVIDER_RECORD","fixture","known-late-usage",5L,2L);
        repository.reconcile(command);repository.reconcile(command);
        var quota=repository.quota(user).stream().filter(q->q.metric()==QuotaData.Metric.TOKENS).findFirst().orElseThrow();assertEquals(0,quota.reserved());assertEquals(0,quota.used());
        assertEquals(7,count("SELECT sum(consumed) FROM pf.quota_bucket WHERE user_id='"+user.userId()+"' AND metric='TOKENS'"));
        assertEquals(1,count("SELECT count(*) FROM pf.model_call WHERE task_id='"+firstTask.id()+"'"));
    }
    @Test void protectedAcceptanceCounterIsSeparateFromUnlimitedBusinessRequestCount() throws Exception {
        var lease=claim(task(user,false));repository.executionStartup(lease);UUID run=UUID.randomUUID();
        for(int i=0;i<20;i++){var call=repository.beginCall(lease,false);repository.permitAcceptanceCall(lease,call,run);}
        var extra=repository.beginCall(lease,false);
        assertEquals("ACCEPTANCE_REQUEST_LIMIT",assertThrows(ChatFailure.class,()->repository.permitAcceptanceCall(lease,extra,run)).code());
        assertEquals(20,count("SELECT physical_requests FROM pf.acceptance_run WHERE id='"+run+"'"));
        assertEquals(21,count("SELECT count(*) FROM pf.model_call WHERE task_id='"+lease.taskId()+"'"));
        repository.stopped(lease,"FAILED","FIXTURE_DONE",null,"f".repeat(64));
        assertEquals(0,repository.quota(user).stream().filter(q->q.metric()==QuotaData.Metric.TOKENS).findFirst().orElseThrow().reserved());
    }
    @Test void claimedAnswerCrashRebindsTheSameAnswerWithoutReplayingTheModel() throws Exception {
        var task=task(user,true);var calls=new AtomicInteger();var provider=provider(calls);
        try(var execution=new ChatExecution(repository,provider,cipher,work)){execution.run(claim(task));}
        var pending=repository.pending(user,task.conversationId()).getFirst();
        repository.answer(user,task.id(),pending.interactionId(),UUID.randomUUID(),new Answer(pending.version(),Decision.EXECUTE_PLAN,"",List.of()));
        var lost=claim(task); // committed claim, then stopped before startup/ACK/execution
        repository.stopped(lost,"BLOCKED","WORKER_INTERRUPTED",null,"2".repeat(64));
        var recovery=repository.recovery(user,task.id());assertTrue(recovery.eligible(),recovery.reasonCode());
        repository.restore(new RecoveryLedger.RestoreCommand(UUID.randomUUID(),task.id(),recovery.checkpointId(),lost.attemptId(),recovery.taskVersion(),recovery.revision(),"fixture"));
        var target=claim(task);
        try(var execution=new ChatExecution(repository,provider,cipher,work)){execution.run(target);}
        assertEquals("SUCCEEDED",repository.task(user,task.id()).status());assertEquals(3,calls.get());
        assertEquals(1,count("SELECT count(*) FROM pf.interaction_answer WHERE task_id='"+task.id()+"'"));
        assertEquals(2,count("SELECT count(*) FROM pf.interaction_resume_binding WHERE task_id='"+task.id()+"'"));
    }
    @Test void optionalQuestionConsumesOneAnswerAtTheNextSafePointWithoutAnotherAttempt() throws Exception {
        var task=task(user,true);var calls=new AtomicInteger();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var delegate=scripted(List.of(
                control("optional","platform_ask_user",Map.of("question","可选风格？","optional",true,"options",List.of())),
                control("write","platform_plan_write",Map.of("title","文本计划","markdown","按文本处理。")),
                control("review","platform_plan_review",Map.of())),calls);
        ChatProvider provider=new ChatProvider(){
            public boolean real(){return false;}
            public CompletionStage<Result> stream(String key,List<HarnessData.Message> input,java.util.function.Consumer<String> text){throw new AssertionError();}
            public Exchange openTyped(String model,String key,List<HarnessData.Message> input,List<HarnessData.ToolDefinition> definitions,java.util.function.Consumer<String> text){
                if(calls.get()==1){entered.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}}
                return delegate.openTyped(model,key,input,definitions,text);
            }
        };
        try(var execution=new ChatExecution(repository,provider,cipher,work)){
            var lease=claim(task);var running=CompletableFuture.runAsync(()->execution.run(lease));
            try{
                assertTrue(entered.await(10,TimeUnit.SECONDS));var q=repository.pending(user,task.conversationId()).getFirst();assertTrue(q.optional());
                UUID command=UUID.randomUUID();var answer=new Answer(q.version(),Decision.ANSWER,"简短",List.of());
                var accepted=repository.answer(user,task.id(),q.interactionId(),command,answer);
                assertEquals(accepted,repository.answer(user,task.id(),q.interactionId(),command,answer));
            }finally{release.countDown();}
            running.get(20,TimeUnit.SECONDS);
        }
        assertEquals(1,count("SELECT count(*) FROM pf.control_input_consumption WHERE task_id='"+task.id()+"'"));
        assertEquals(1,count("SELECT count(*) FROM pf.attempt WHERE task_id='"+task.id()+"'"));
        assertEquals("WAITING_INPUT",repository.task(user,task.id()).status());repository.cancel(user,task.id(),UUID.randomUUID());
    }
    @Test void safeCheckpointRecoveryPreservesActivityBudgetAcrossAUserWait() throws Exception {
        var lease=claim(task(user,false));var startup=repository.executionStartup(lease);
        byte[] state=("{\"user_id\":\""+lease.userId()+"\",\"session_id\":\""+lease.conversationId()+"\",\"context\":[]}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        UUID checkpoint=repository.commitCheckpoint(lease,ChatRuntime.bundle(),new HarnessData.CheckpointDraft(0,state,startup.history(),HarnessData.CheckpointPhase.INPUT_READY));
        repository.stopped(lease,"BLOCKED","WORKER_INTERRUPTED",null,"0".repeat(64));
        sql("UPDATE pf.task SET active_millis=70000,execution_deadline_at=clock_timestamp()-interval '1 day' WHERE id='"+lease.taskId()+"'");
        var recovery=repository.recovery(user,lease.taskId());assertTrue(recovery.eligible());
        repository.restore(new RecoveryLedger.RestoreCommand(UUID.randomUUID(),lease.taskId(),checkpoint,lease.attemptId(),recovery.taskVersion(),recovery.revision(),"fixture"));
        var target=claim(repository.task(user,lease.taskId()));var restored=repository.executionStartup(target);assertTrue(restored.restoring());
        long remaining=java.time.Duration.between(java.time.Instant.now(),restored.deadline()).toMillis();assertTrue(remaining>45000&&remaining<=50000);
        assertNotEquals(lease.attemptId(),target.attemptId());assertEquals(lease.taskId(),target.taskId());
        repository.stopped(target,"FAILED","FIXTURE_DONE",null,"1".repeat(64));
    }
    HarnessData.Round control(String id,String name,Map<String,Object> arguments){return new HarnessData.Round("",List.of(new HarnessData.Call(id,name,arguments,false)),"tool_calls",10,4);}
    ChatProvider scripted(List<HarnessData.Round> rounds,AtomicInteger count){return new ChatProvider(){
        public boolean real(){return false;}
        public CompletionStage<Result> stream(String key,List<HarnessData.Message> input,java.util.function.Consumer<String> onText){throw new AssertionError("Typed path required");}
        public Exchange openTyped(String model,String key,List<HarnessData.Message> input,List<HarnessData.ToolDefinition> definitions,java.util.function.Consumer<String> onText){
            int index=count.getAndIncrement();if(index>=rounds.size())throw new AssertionError("Unexpected model replay");var round=rounds.get(index);
            var result=CompletableFuture.completedFuture(new Result(round,true,"script-"+index));
            return new Exchange(){public CompletionStage<Result> result(){return result;}public CompletionStage<Void> terminated(){return CompletableFuture.completedFuture(null);}public void cancel(){}};
        }
    };}
    ChatProvider provider(AtomicInteger count){return new ChatProvider(){
        public boolean real(){return false;}
        public CompletionStage<Result> stream(String key,List<HarnessData.Message> input,java.util.function.Consumer<String> onText){throw new AssertionError("Typed path required");}
        public Exchange openTyped(String model,String key,List<HarnessData.Message> input,List<HarnessData.ToolDefinition> definitions,java.util.function.Consumer<String> onText){
            int n=count.incrementAndGet();HarnessData.Round round;
            if(n==1)round=new HarnessData.Round("",List.of(new HarnessData.Call("write-1","platform_plan_write",Map.of("title","文本计划","markdown","按步骤处理文字，不访问文件。"),false)),"tool_calls",10,4);
            else if(n==2)round=new HarnessData.Round("",List.of(new HarnessData.Call("review-1","platform_plan_review",Map.of(),false)),"tool_calls",10,4);
            else {String text="文本续接完成666";onText.accept(text);round=new HarnessData.Round(text,List.of(),"stop",10,4);}
            var result=CompletableFuture.completedFuture(new Result(round,true,"fixture-"+n));
            return new Exchange(){public CompletionStage<Result> result(){return result;}public CompletionStage<Void> terminated(){return CompletableFuture.completedFuture(null);}public void cancel(){}};
        }
    };}
}
