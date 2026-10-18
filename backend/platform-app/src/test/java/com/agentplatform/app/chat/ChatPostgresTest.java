package com.agentplatform.app.chat;

import static org.junit.jupiter.api.Assertions.*;

import com.agentplatform.domain.chat.ChatData.*;
import com.agentplatform.domain.chat.ChatFailure;
import com.agentplatform.infrastructure.chat.CredentialCipher;
import com.agentplatform.infrastructure.chat.JdbcChatRepository;
import com.agentplatform.infrastructure.identity.JdbcIdentityStore;
import com.agentplatform.types.harness.HarnessData.Round;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Explicit real PostgreSQL selection; separate from the browser/application database. */
class ChatPostgresTest {
    private static String url, password;
    @TempDir Path workspace;
    private JdbcChatRepository repository;
    private JdbcIdentityStore identity;
    private com.zaxxer.hikari.HikariDataSource pool;
    private final CredentialCipher cipher = new CredentialCipher(new byte[32]);
    private Actor alice, bob;
    private final java.util.Map<UUID, Envelope> published = new java.util.HashMap<>();
    private static final UUID MODEL = UUID.fromString("0199e000-0000-7000-8000-000000000002");
    private static final String SENTINEL = "r12-database-sentinel-no-real-api-key";

    @BeforeAll static void migrate() throws Exception {
        url = System.getenv("R12_TEST_JDBC_URL");
        Assumptions.assumeTrue(url != null, "Real R1.2 PostgreSQL not configured; not acceptance evidence");
        assertTrue(url.startsWith("jdbc:postgresql://127.0.0.1:15442/chat_test?") || ("1".equals(System.getenv("R21_ACCEPTANCE")) && url.startsWith("jdbc:postgresql://127.0.0.1:15443/chat_test?")), "Refusing another project's database");
        password = Files.readString(Path.of(System.getenv("R12_TEST_PASSWORD_FILE"))).strip();
        // Destructive reset is restricted above to this explicitly named, isolated test database.
        Flyway.configure().dataSource(url, "postgres", password).defaultSchema("pf").schemas("pf", "pf_runtime")
                .cleanDisabled(false).load().clean();
        for (String target : List.of("1", "2", "3", "4", "5", "6")) {
            Flyway.configure().dataSource(url, "postgres", password).defaultSchema("pf")
                    .schemas("pf", "pf_runtime").target(target).load().migrate();
        }
    }

    @BeforeEach void provision() throws Exception {
        sql("TRUNCATE pf.app_user CASCADE");
        sql("DELETE FROM pf.model_definition WHERE model_name='deepseek-v4-pro'");
        sql("INSERT INTO pf.execution_slot(id) VALUES(1) ON CONFLICT(id) DO NOTHING");
        sql("UPDATE pf.execution_slot SET task_id=NULL,owner_id=NULL,lease_until=NULL");
        sql("UPDATE pf.acceptance_model_meter SET physical_calls=0,reserved_micros=0,consumed_micros=0");
        var config = new com.zaxxer.hikari.HikariConfig();
        config.setJdbcUrl(url); config.setUsername("postgres"); config.setPassword(password);
        config.setMaximumPoolSize(4); config.setMinimumIdle(1); config.setConnectionTimeout(500);
        pool = new com.zaxxer.hikari.HikariDataSource(config);
        repository = new JdbcChatRepository(pool);
        identity = new JdbcIdentityStore(pool);
        alice = actor("alice", true);
        bob = actor("bob", false);
    }
    @AfterEach void closePool() { if (pool != null) pool.close(); }

    private Actor actor(String name, boolean admin) {
        UUID user = identity.provision(UUID.randomUUID(), name, name, "test-only-unused-password-hash", admin);
        return new Actor(user, identity.spaces(user).getFirst().id());
    }
    private UUID key(String scope, Actor actor) {
        return repository.importCredential(cipher.encrypt(UUID.randomUUID(), scope,
                "USER".equals(scope) ? actor.spaceId() : null, "USER".equals(scope) ? actor.userId() : null, SENTINEL));
    }
    private Task submit(Actor actor) {
        var conversation = repository.create(actor, UUID.randomUUID(), "Test conversation");
        return repository.submit(actor, conversation.id(), UUID.randomUUID(), MODEL, "你好😀");
    }
    private Lease claim(Task task) {
        repository.publications(UUID.randomUUID()).forEach(item -> published.put(item.envelope().aggregateId(), item.envelope()));
        return repository.claim(java.util.Objects.requireNonNull(published.get(task.id())), UUID.randomUUID());
    }
    private static void sql(String value) throws Exception {
        try(var c = DriverManager.getConnection(url, "postgres", password); var s = c.createStatement()) { s.execute(value); }
    }
    private static long count(String value) throws Exception {
        try(var c = DriverManager.getConnection(url, "postgres", password); var s = c.createStatement(); var r = s.executeQuery(value)) { r.next(); return r.getLong(1); }
    }
    private static void denied(String code, Runnable work) {
        assertEquals(code, assertThrows(ChatFailure.class, work::run).code());
    }

    @Test void platformKeyRequiresExplicitBindingAndIsSharedOnlyByPolicy() {
        UUID key = key("PLATFORM", null);
        assertTrue(repository.models(alice).isEmpty());
        assertTrue(repository.models(bob).isEmpty());
        repository.bind(MODEL, alice, key, "PLATFORM");
        assertEquals(1, repository.models(alice).size());
        assertTrue(repository.models(bob).isEmpty());
        repository.bind(MODEL, bob, key, "PLATFORM");
        assertEquals(1, repository.models(bob).size());
        denied("CREDENTIAL_SCOPE_REJECTED", () -> repository.bind(MODEL, alice, key, "USER"));
    }

    @Test void userKeyCannotBindToAnotherOwnerEvenIfActorIsAdministrator() {
        UUID key = key("USER", bob);
        repository.bind(MODEL, bob, key, "USER");
        denied("CREDENTIAL_SCOPE_REJECTED", () -> repository.bind(MODEL, alice, key, "USER"));
        denied("CREDENTIAL_SCOPE_REJECTED", () -> repository.bind(MODEL, alice, key, "PLATFORM"));
        assertTrue(repository.models(alice).isEmpty());
        var task = submit(bob);
        denied("RESOURCE_NOT_FOUND", () -> repository.task(alice, task.id()));
        denied("RESOURCE_NOT_FOUND", () -> repository.snapshot(alice, task.conversationId()));
    }

    @Test void revokeFrozenKeyRejectsNextResolutionWithoutFallingBack() throws Exception {
        UUID platform = key("PLATFORM", null), user = key("USER", bob);
        repository.bind(MODEL, bob, user, "USER");
        var task = submit(bob);
        var lease = claim(task);
        var call = repository.beginCall(lease, false);
        repository.bind(MODEL, bob, platform, "PLATFORM");
        // A queued/running task retains its explicitly frozen source.
        assertEquals(user, repository.credential(lease, call.id()).id());
        repository.revokeCredential(user);
        repository.revokeCredential(user);
        denied("CREDENTIAL_REVOKED", () -> repository.credential(lease, call.id()));
        assertEquals(1, count("SELECT count(*) FROM pf.credential_access_audit"));
        assertEquals(1, count("SELECT revision FROM pf.credential WHERE id='" + platform + "'"));
        assertEquals(2, count("SELECT revision FROM pf.credential WHERE id='" + user + "'"));
        repository.settle(lease, call, null, null, "CALL_NOT_SENT", false);
        repository.finish(lease, "FAILED", "CREDENTIAL_REVOKED");
    }

    @Test void ciphertextIsNotPlaintextAndApiDatabaseRoleCannotReadIt() throws Exception {
        UUID id = key("USER", alice);
        try(var c = DriverManager.getConnection(url, "postgres", password); var s = c.createStatement()) {
            try(var r = s.executeQuery("SELECT ciphertext,nonce FROM pf.credential WHERE id='" + id + "'")) {
                assertTrue(r.next()); assertFalse(new String(r.getBytes(1), StandardCharsets.UTF_8).contains(SENTINEL));
                assertEquals(12, r.getBytes(2).length);
            }
            s.execute("DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='ap_r12_metadata_test') THEN CREATE ROLE ap_r12_metadata_test NOLOGIN; END IF; END $$");
            s.execute("GRANT USAGE ON SCHEMA pf TO ap_r12_metadata_test");
            s.execute("GRANT SELECT(id,scope_type,space_id,owner_user_id,status,revision) ON pf.credential TO ap_r12_metadata_test");
            s.execute("SET ROLE ap_r12_metadata_test");
            try(var r = s.executeQuery("SELECT id,status FROM pf.credential")) { assertTrue(r.next()); }
            var denied = assertThrows(java.sql.SQLException.class, () -> s.executeQuery("SELECT ciphertext FROM pf.credential"));
            assertEquals("42501", denied.getSQLState());
        }
    }

    @Test void concurrentSameIdempotencyKeyCreatesOneTaskAndOneReservation() throws Exception {
        repository.bind(MODEL, alice, key("PLATFORM", null), "PLATFORM");
        var conversation = repository.create(alice, UUID.randomUUID(), "Concurrent");
        UUID key = UUID.randomUUID(); var gate = new CountDownLatch(1);
        try(var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { gate.await(); return repository.submit(alice, conversation.id(), key, MODEL, "same"); });
            var second = pool.submit(() -> { gate.await(); return repository.submit(alice, conversation.id(), key, MODEL, "same"); });
            gate.countDown(); assertEquals(first.get().id(), second.get().id());
        }
        assertEquals(1, count("SELECT count(*) FROM pf.task"));
        assertEquals(1, count("SELECT count(*) FROM pf.event_outbox"));
        assertEquals(36864, count("SELECT reserved_tokens FROM pf.budget_account WHERE user_id='" + alice.userId() + "'"));
        denied("IDEMPOTENCY_CONFLICT", () -> repository.submit(alice, conversation.id(), key, MODEL, "different"));
    }

    @Test void rejectedBudgetLeavesNoPartialSubmission() throws Exception {
        repository.bind(MODEL, alice, key("PLATFORM", null), "PLATFORM");
        sql("UPDATE pf.budget_account SET cost_limit_micros=1");
        denied("QUOTA_EXCEEDED", () -> submit(alice));
        for(String table : List.of("task", "message", "event_outbox", "chat_config_snapshot")) {
            assertEquals(0, count("SELECT count(*) FROM pf." + table));
        }
    }

    @Test void knownUsageSettlesOnceAndUnknownUsageRemainsReserved() throws Exception {
        repository.bind(MODEL, alice, key("PLATFORM", null), "PLATFORM");
        var lease = claim(submit(alice));
        var first = repository.beginCall(lease, false);
        repository.startCall(lease, first, false);
        var result = new Round("answer", List.of(), "stop", 100, 10);
        repository.settle(lease, first, result, "fixture-id", null, false);
        repository.settle(lease, first, result, "fixture-id", null, false);
        var second = repository.beginCall(lease, false);
        repository.startCall(lease, second, false);
        repository.settle(lease, second, null, null, "PROVIDER_USAGE_MISSING", false);
        repository.finish(lease, "FAILED", "PROVIDER_REQUEST_FAILED");
        assertEquals(110, count("SELECT consumed_tokens FROM pf.budget_account"));
        assertEquals(9216, count("SELECT reserved_tokens FROM pf.budget_account"));
        assertEquals(2, count("SELECT count(*) FROM pf.model_call WHERE status='COMPLETED'"));
        assertEquals(1, count("SELECT count(*) FROM pf.model_call WHERE input_tokens IS NULL AND output_tokens IS NULL AND cost_micros IS NULL"));
        assertEquals(1, count("SELECT count(*) FROM pf.budget_reservation WHERE state='RECONCILING'"));
        assertEquals(0, count("SELECT count(*) FROM pf.execution_slot WHERE task_id IS NOT NULL"));
    }

    @Test void liveSlotAndOldFencePreventSecondOwnerAndPostLeaseWrites() throws Exception {
        repository.bind(MODEL, alice, key("PLATFORM", null), "PLATFORM");
        var first = submit(alice); var second = submit(alice);
        var lease = claim(first);
        assertNotNull(lease); assertNull(claim(second));
        sql("UPDATE pf.execution_slot SET lease_until=now()-interval '1 second'");
        denied("EXECUTION_FENCE_LOST", () -> repository.delta(lease, "forbidden"));
        denied("EXECUTION_FENCE_LOST", () -> repository.beginCall(lease, false));
        // Expiry alone does not clear ownership or replay a model call.
        assertEquals(1, count("SELECT count(*) FROM pf.execution_slot WHERE task_id IS NOT NULL"));
    }

    @Test void preparedButUnsentCallReleasesItsReservationOnFailure() throws Exception {
        repository.bind(MODEL, alice, key("PLATFORM", null), "PLATFORM");
        var lease = claim(submit(alice));
        repository.beginCall(lease, false);
        repository.finish(lease, "FAILED", "CREDENTIAL_REVOKED");
        assertEquals(0, count("SELECT reserved_tokens FROM pf.budget_account"));
        assertEquals(1, count("SELECT count(*) FROM pf.model_call WHERE status='FAILED' AND error_code='CALL_NOT_SENT'"));
        assertEquals(0, count("SELECT count(*) FROM pf.budget_reservation WHERE state='RECONCILING'"));
    }

    @Test void expiredEventsRequireSnapshotAndNewSnapshotCursorRemainsValid() throws Exception {
        repository.bind(MODEL, alice, key("PLATFORM", null), "PLATFORM");
        var task = submit(alice); long cursor = repository.snapshot(alice, task.conversationId()).cursor();
        sql("UPDATE pf.platform_event SET created_at=now()-interval '31 days'");
        denied("CURSOR_EXPIRED", () -> repository.events(alice, task.conversationId(), 0));
        assertTrue(repository.events(alice, task.conversationId(), cursor).isEmpty());
    }

    @Test void harnessEmptyOutputRepairsOnceWithoutHiddenSdkModelReplay() throws Exception {
        repository.bind(MODEL, alice, key("PLATFORM", null), "PLATFORM");
        var lease = claim(submit(alice)); var calls = new java.util.concurrent.atomic.AtomicInteger();
        var provider = new com.agentplatform.domain.chat.ChatProvider() {
            public boolean real() { return false; }
            public java.util.concurrent.CompletionStage<Result> stream(String key, List<com.agentplatform.types.harness.HarnessData.Message> messages, java.util.function.Consumer<String> text) {
                calls.incrementAndGet();
                return java.util.concurrent.CompletableFuture.completedFuture(new Result(new Round("", List.of(), "stop", 10, 0), true, "fixture-empty"));
            }
        };
        var storage = new com.agentplatform.infrastructure.storage.PostgresStateStorage(pool);
        try(var execution = new ChatExecution(repository, provider, cipher, storage, workspace)) { execution.run(lease); }
        assertEquals(2, calls.get(), "One initial physical call and one empty repair only");
        assertEquals("FAILED", repository.task(alice, lease.taskId()).status());
        assertEquals(2, count("SELECT count(*) FROM pf.model_call"));
    }

    @Test void boundedPoolExhaustionRejectsAuthorizationThenRecovers() throws Exception {
        try(var one=pool.getConnection();var two=pool.getConnection();var three=pool.getConnection();var four=pool.getConnection()) {
            denied("CHAT_STORAGE_UNAVAILABLE", () -> repository.models(alice));
        }
        assertTrue(repository.models(alice).isEmpty());
        assertEquals(4, pool.getMaximumPoolSize());
    }

    @Test void queuedCancellationCommitsTerminalMessageEventAndReleasesBudget() throws Exception {
        repository.bind(MODEL, alice, key("PLATFORM", null), "PLATFORM");
        var task = submit(alice);
        assertEquals("CANCELLED", repository.cancel(alice, task.id(), UUID.randomUUID()).status());
        assertEquals(0, count("SELECT reserved_tokens FROM pf.budget_account"));
        var snapshot = repository.snapshot(alice, task.conversationId());
        assertEquals("FAILED", snapshot.messages().stream().filter(m -> m.role().equals("ASSISTANT")).findFirst().orElseThrow().status());
        assertTrue(repository.events(alice, task.conversationId(), 0).stream().anyMatch(e -> e.type().equals("message.completed") && e.payload().get("status").equals("FAILED")));
        assertEquals(0, count("SELECT count(*) FROM pf.model_call"));
    }

    @Test void forgedAttemptAndUnrelatedCallCannotReadCredentialOrSettle() throws Exception {
        repository.bind(MODEL, alice, key("PLATFORM", null), "PLATFORM");
        var lease = claim(submit(alice)); var call = repository.beginCall(lease, false);
        var forged = new Lease(lease.taskId(), lease.spaceId(), lease.userId(), lease.conversationId(), UUID.randomUUID(), lease.ownerId(), lease.fence(), lease.modelId(), lease.credentialId(), lease.credentialRevision(), lease.assistantId());
        denied("EXECUTION_FENCE_LOST", () -> repository.credential(forged, call.id()));
        denied("CALL_NOT_FOUND", () -> repository.credential(lease, UUID.randomUUID()));
        denied("CALL_NOT_FOUND", () -> repository.settle(lease, new Call(UUID.randomUUID(), 1, call.priceVersion()), null, null, "CALL_NOT_SENT", false));
        assertEquals(0, count("SELECT count(*) FROM pf.credential_access_audit"));
    }

    @Test void revokedQueuedOwnerFinalizesMessageWithoutCallingProvider() throws Exception {
        repository.bind(MODEL, alice, key("PLATFORM", null), "PLATFORM");
        var task = submit(alice);
        sql("UPDATE pf.app_user SET status='DISABLED' WHERE id='" + alice.userId() + "'");
        assertNull(claim(task));
        assertEquals(1, count("SELECT count(*) FROM pf.task WHERE status='FAILED' AND reason_code='AUTHORIZATION_REVOKED'"));
        assertEquals(1, count("SELECT count(*) FROM pf.message WHERE role='ASSISTANT' AND status='FAILED'"));
        assertEquals(1, count("SELECT count(*) FROM pf.platform_event WHERE type='message.completed'"));
        assertEquals(0, count("SELECT reserved_tokens FROM pf.budget_account"));
        assertEquals(0, count("SELECT count(*) FROM pf.model_call"));
    }

    @Test void stoppedOwnerKeepsUnknownCallAndPartialMessageWithoutReplay() throws Exception {
        repository.bind(MODEL, alice, key("PLATFORM", null), "PLATFORM");
        var lease = claim(submit(alice)); var call = repository.beginCall(lease, false);
        repository.startCall(lease, call, false); repository.delta(lease, "partial😀");
        denied("STOPPED_OWNER_PROOF_REQUIRED", () -> repository.recoverStopped(lease.ownerId()));
        sql("UPDATE pf.execution_slot SET lease_until=now()-interval '1 second'");
        var proof = new com.agentplatform.domain.chat.ExecutionLedger.StoppedOwner(lease.ownerId(),lease.taskId(),lease.attemptId(),lease.fence(),"a".repeat(64),"sha256:"+"b".repeat(64),java.time.Instant.now().minusSeconds(60),java.time.Instant.now(),java.time.Instant.now().plusMillis(1),"c".repeat(64));
        repository.recoverStopped(proof); repository.recoverStopped(proof);
        assertEquals(1, count("SELECT count(*) FROM pf.model_call WHERE status='UNKNOWN'"));
        assertEquals(1, count("SELECT count(*) FROM pf.budget_reservation WHERE state='RECONCILING'"));
        assertEquals(1, count("SELECT count(*) FROM pf.message WHERE role='ASSISTANT' AND status='PARTIAL'"));
        assertEquals(1, count("SELECT count(*) FROM pf.platform_event WHERE type='message.completed'"));
        assertEquals(0, count("SELECT count(*) FROM pf.execution_slot WHERE task_id IS NOT NULL"));
        denied("EXECUTION_FENCE_LOST", () -> repository.delta(lease, "late"));
    }

    private com.rabbitmq.client.ConnectionFactory broker() throws Exception {
        String file = System.getenv("R12_TEST_MQ_PASSWORD_FILE");
        Assumptions.assumeTrue(file != null, "Real isolated RabbitMQ fixture not configured");
        var factory = new com.rabbitmq.client.ConnectionFactory();
        factory.setHost("127.0.0.1"); factory.setPort("1".equals(System.getenv("R21_ACCEPTANCE")) ? 15693 : 15692);
        factory.setVirtualHost("/agentplatform-r12-test"); factory.setUsername("ap_operator");
        factory.setPassword(Files.readString(Path.of(file)).strip());
        factory.setAutomaticRecoveryEnabled(false); factory.setConnectionTimeout(2000);
        return factory;
    }

    private void topology(com.rabbitmq.client.Channel channel) throws Exception {
        channel.exchangeDeclare("ap.commands.v1", "direct", true);
        channel.queueDeclare("ap.execution.v1", true, false, false, null);
        channel.queueBind("ap.execution.v1", "ap.commands.v1", "execution.ready");
        channel.queuePurge("ap.execution.v1");
    }

    private com.rabbitmq.client.GetResponse received(com.rabbitmq.client.Channel channel) throws Exception {
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        com.rabbitmq.client.GetResponse response;
        do { response = channel.basicGet("ap.execution.v1", false); if(response != null) return response; Thread.sleep(25); }
        while(System.nanoTime() < until);
        throw new AssertionError("Expected actual RabbitMQ delivery");
    }

    @Test void actualAckLossRedeliversAfterDurableClaimWithoutSecondOwner() throws Exception {
        var factory = broker();
        repository.bind(MODEL, alice, key("PLATFORM", null), "PLATFORM");
        var task = submit(alice); var item = repository.publications(UUID.randomUUID()).getFirst();
        try(var connection = factory.newConnection();
                var transport = new com.agentplatform.infrastructure.chat.RabbitDispatchTransport(factory, repository)) {
            var first = connection.createChannel();
            topology(first); transport.publish(item.envelope());
            var delivery = received(first);
            var lease = repository.claim(item.envelope(), UUID.randomUUID()); assertNotNull(lease);
            // The broker has delivered, the DB owner committed, but no basicAck is ever sent.
            first.abort();
            try(var second = connection.createChannel()) {
                var redelivery = received(second); assertTrue(redelivery.getEnvelope().isRedeliver());
                assertArrayEquals(delivery.getBody(), redelivery.getBody());
                assertNull(repository.claim(item.envelope(), UUID.randomUUID()));
                second.basicAck(redelivery.getEnvelope().getDeliveryTag(), false);
                assertEquals(1, count("SELECT count(*) FROM pf.attempt"));
                assertEquals(1, count("SELECT count(*) FROM pf.consumer_inbox WHERE disposition='CLAIMED'"));
                repository.finish(lease, "FAILED", "FIXTURE_FINISHED");
            }
        }
    }

    @Test void brokerDeliveryWithoutObservedConfirmRepublishesSameOutboxIdentity() throws Exception {
        var factory = broker(); UUID relay = UUID.randomUUID();
        repository.bind(MODEL, alice, key("PLATFORM", null), "PLATFORM"); submit(alice);
        var item = repository.publications(relay).getFirst();
        try(var connection = factory.newConnection();var receiver = connection.createChannel();
                var transport = new com.agentplatform.infrastructure.chat.RabbitDispatchTransport(factory, repository)) {
            var publisher = connection.createChannel();
            topology(receiver); publisher.confirmSelect();
            publisher.basicPublish("ap.commands.v1", "execution.ready", true,
                    new com.rabbitmq.client.AMQP.BasicProperties.Builder().deliveryMode(2).build(),
                    tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsBytes(item.envelope()));
            received(receiver); // Actual arrival proves publication; application intentionally never awaits confirmation.
            publisher.abort(); repository.published(relay, item, false);
            assertEquals(1, count("SELECT count(*) FROM pf.event_outbox WHERE status='PENDING'"));
            sql("UPDATE pf.event_outbox SET available_at=now()");
            var retry = repository.publications(relay).getFirst();
            assertEquals(item.envelope().eventId(), retry.envelope().eventId());
            transport.publish(retry.envelope()); repository.published(relay, retry, true);
            var duplicate = received(receiver);
            var lease = repository.claim(item.envelope(), UUID.randomUUID()); assertNotNull(lease);
            assertNull(repository.claim(retry.envelope(), UUID.randomUUID()));
            receiver.basicAck(duplicate.getEnvelope().getDeliveryTag(), true);
            assertEquals(1, count("SELECT count(*) FROM pf.attempt"));
            assertEquals(1, count("SELECT count(*) FROM pf.event_outbox WHERE status='PUBLISHED'"));
            repository.finish(lease, "FAILED", "FIXTURE_FINISHED");
        }
    }

    @Test void failedDurableClaimRollsBackSlotAttemptAndInboxBeforeRedelivery() throws Exception {
        repository.bind(MODEL, alice, key("PLATFORM", null), "PLATFORM"); var task = submit(alice);
        var envelope = repository.publications(UUID.randomUUID()).getFirst().envelope();
        sql("CREATE FUNCTION pf.reject_claim_fixture() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture rollback'; END $$;"
                + "CREATE TRIGGER reject_claim_fixture BEFORE INSERT ON pf.attempt FOR EACH ROW EXECUTE FUNCTION pf.reject_claim_fixture()");
        try { denied("CHAT_STORAGE_UNAVAILABLE", () -> repository.claim(envelope, UUID.randomUUID())); }
        finally { sql("DROP TRIGGER reject_claim_fixture ON pf.attempt; DROP FUNCTION pf.reject_claim_fixture()"); }
        assertEquals(0, count("SELECT count(*) FROM pf.execution_slot WHERE task_id IS NOT NULL"));
        assertEquals(0, count("SELECT count(*) FROM pf.attempt"));
        assertEquals(0, count("SELECT count(*) FROM pf.consumer_inbox"));
        assertEquals("QUEUED", repository.task(alice, task.id()).status());
        assertNotNull(repository.claim(envelope, UUID.randomUUID()));
    }

    @Test void sameConversationOrderingAndDispatchGenerationRejectStaleWakeups() throws Exception {
        repository.bind(MODEL, alice, key("PLATFORM", null), "PLATFORM");
        var conversation = repository.create(alice, UUID.randomUUID(), "Ordered");
        var first = repository.submit(alice, conversation.id(), UUID.randomUUID(), MODEL, "first");
        var second = repository.submit(alice, conversation.id(), UUID.randomUUID(), MODEL, "second");
        var items = repository.publications(UUID.randomUUID());
        var older = items.stream().filter(i -> i.envelope().aggregateId().equals(first.id())).findFirst().orElseThrow().envelope();
        var newer = items.stream().filter(i -> i.envelope().aggregateId().equals(second.id())).findFirst().orElseThrow().envelope();
        assertNull(repository.claim(newer, UUID.randomUUID()));
        var lease = repository.claim(older, UUID.randomUUID()); assertNotNull(lease);
        repository.finish(lease, "FAILED", "FIXTURE_FINISHED");
        sql("UPDATE pf.execution_dispatch SET available_at=now() WHERE status='DEFERRED'"); repository.wakeDeferred();
        var wake = repository.publications(UUID.randomUUID()).getFirst().envelope();
        assertEquals(2, wake.generation()); assertEquals(second.id(), wake.aggregateId());
        assertNull(repository.claim(newer, UUID.randomUUID()));
        assertNotNull(repository.claim(wake, UUID.randomUUID()));
        assertEquals(2, count("SELECT count(*) FROM pf.attempt"));
    }

    @Test void perTaskCallLimitAndAcceptanceMeterRemainDurableAcrossRepositoryRecreation() throws Exception {
        repository.bind(MODEL, alice, key("PLATFORM", null), "PLATFORM");
        var lease = claim(submit(alice));
        for(int i=0;i<4;i++) {
            var call = repository.beginCall(lease, false); repository.startCall(lease, call, false);
            repository.settle(lease, call, null, null, "PROVIDER_RATE_LIMITED", false);
        }
        denied("MODEL_CALL_LIMIT", () -> repository.beginCall(lease, false));
        repository.finish(lease, "FAILED", "FIXTURE_FINISHED");
        var next = claim(submit(alice)); var call = repository.beginCall(next, true);
        sql("UPDATE pf.acceptance_model_meter SET physical_calls=20,reserved_micros=990000,consumed_micros=100000");
        var recreated = new JdbcChatRepository(pool);
        recreated.startCall(next, call, true);
        assertEquals(21,count("SELECT physical_calls FROM pf.acceptance_model_meter"));
        assertTrue(count("SELECT reserved_micros FROM pf.acceptance_model_meter")>990000);
        assertEquals(100000,count("SELECT consumed_micros FROM pf.acceptance_model_meter"));
        denied("CALL_ALREADY_STARTED",()->recreated.startCall(next,call,true));
        assertEquals(21,count("SELECT physical_calls FROM pf.acceptance_model_meter"));
        // This is a test-only ledger boundary test; no network/provider calls occur.
    }

    @Test void requiredContextOverflowBlocksBeforeAnyPhysicalCall() throws Exception {
        repository.bind(MODEL, alice, key("PLATFORM", null), "PLATFORM");
        var conversation = repository.create(alice, UUID.randomUUID(), "Context boundary");
        var task = repository.submit(alice, conversation.id(), UUID.randomUUID(), MODEL, "中".repeat(2200));
        var lease = claim(task);
        denied("CONTEXT_REQUIRED_TOO_LARGE", () -> repository.history(lease));
        repository.finish(lease, "BLOCKED", "CONTEXT_REQUIRED_TOO_LARGE");
        assertEquals(0, count("SELECT count(*) FROM pf.model_call"));
        assertEquals(0, count("SELECT reserved_tokens FROM pf.budget_account"));
        assertEquals("BLOCKED", repository.task(alice, task.id()).status());
    }

    @Test void conversationCursorFollowsCreatedAtAndIdRatherThanUuidClockOrder() throws Exception {
        var first = repository.create(alice, UUID.randomUUID(), "First");
        var second = repository.create(alice, UUID.randomUUID(), "Second");
        var third = repository.create(alice, UUID.randomUUID(), "Third");
        sql("UPDATE pf.conversation SET created_at='2026-01-01T00:00:00Z';"
                + "UPDATE pf.conversation SET created_at='2026-01-03T00:00:00Z' WHERE id='" + first.id() + "';"
                + "UPDATE pf.conversation SET created_at='2026-01-02T00:00:00Z' WHERE id='" + second.id() + "'");
        assertEquals(List.of(first.id(), second.id()), repository.conversations(alice, null, 2).stream().map(Conversation::id).toList());
        assertEquals(List.of(third.id()), repository.conversations(alice, second.id(), 2).stream().map(Conversation::id).toList());
        assertTrue(repository.conversations(bob, second.id(), 2).isEmpty());
    }

    @Test void competingDifferentSubmissionsCannotOversellTheLastBudgetReservation() throws Exception {
        repository.bind(MODEL, alice, key("PLATFORM", null), "PLATFORM");
        var first = repository.create(alice, UUID.randomUUID(), "Budget one");
        var second = repository.create(alice, UUID.randomUUID(), "Budget two");
        sql("UPDATE pf.budget_account SET token_limit=36864,cost_limit_micros=98304");
        var gate = new CountDownLatch(1);
        try(var threads = Executors.newFixedThreadPool(2)) {
            java.util.function.Function<UUID,String> submit = id -> {
                try { gate.await(); repository.submit(alice, id, UUID.randomUUID(), MODEL, "Budget race"); return "ACCEPTED"; }
                catch(ChatFailure denied) { return denied.code(); }
                catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new RuntimeException(interrupted); }
            };
            var one = threads.submit(() -> submit.apply(first.id())); var two = threads.submit(() -> submit.apply(second.id()));
            gate.countDown();
            assertEquals(java.util.Set.of("ACCEPTED", "QUOTA_EXCEEDED"), java.util.Set.of(one.get(), two.get()));
        }
        assertEquals(1, count("SELECT count(*) FROM pf.task"));
        assertEquals(1, count("SELECT count(*) FROM pf.event_outbox"));
        assertEquals(36864, count("SELECT reserved_tokens FROM pf.budget_account"));
        assertEquals(98304, count("SELECT reserved_micros FROM pf.budget_account"));
    }
    @Test void firstTaskIsAtomicAndExactBodyReplayIgnoresChangedDefaults() throws Exception {
        repository.bind(MODEL, alice, key("PLATFORM", null), "PLATFORM");UUID request=UUID.randomUUID();
        var first=repository.first(alice,request,"Draft",null,null,"First 🧪");
        repository.preferences(alice,0,java.util.Map.of("defaultCredentialSource","USER"));
        var again=repository.first(alice,request,"Draft",null,null,"First 🧪");
        assertEquals(first.task().id(),again.task().id());assertEquals(1,count("SELECT count(*) FROM pf.conversation"));
        assertEquals(1,count("SELECT count(*) FROM pf.task"));assertEquals(1,count("SELECT count(*) FROM pf.event_outbox"));
        assertEquals(List.of("USER","ASSISTANT"),repository.snapshot(alice,first.conversation().id()).messages().stream().map(Message::role).toList());
        denied("IDEMPOTENCY_CONFLICT",()->repository.first(alice,request,"Draft",null,null,"changed"));
        assertEquals("PLATFORM",((java.util.Map<?,?>)repository.configuration(alice,first.task().id()).get("values")).get("credentialSource"));
    }
    @Test void firstTaskFailureRollsBackConversationBudgetAndOutbox() throws Exception {
        repository.bind(MODEL,alice,key("PLATFORM",null),"PLATFORM");
        denied("CREDENTIAL_SOURCE_UNAVAILABLE",()->repository.first(alice,UUID.randomUUID(),"Draft",MODEL,"USER","hello"));
        assertEquals(0,count("SELECT count(*) FROM pf.conversation"));assertEquals(0,count("SELECT count(*) FROM pf.task"));assertEquals(0,count("SELECT sum(reserved_micros) FROM pf.budget_account"));
        sql("UPDATE pf.budget_account SET cost_limit_micros=1");
        denied("QUOTA_EXCEEDED",()->repository.first(alice,UUID.randomUUID(),"Draft",MODEL,"PLATFORM","hello"));
        assertEquals(0,count("SELECT count(*) FROM pf.event_outbox"));assertEquals(0,count("SELECT count(*) FROM pf.chat_config_snapshot"));
    }
    @Test void concurrentInitialSubmissionCreatesExactlyOneConversation() throws Exception {
        repository.bind(MODEL,alice,key("PLATFORM",null),"PLATFORM");UUID request=UUID.randomUUID();var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->{gate.await();return repository.first(alice,request,"Draft",MODEL,"PLATFORM","same");});
            var b=pool.submit(()->{gate.await();return repository.first(alice,request,"Draft",MODEL,"PLATFORM","same");});gate.countDown();
            assertEquals(a.get().task().id(),b.get().task().id());
        }assertEquals(1,count("SELECT count(*) FROM pf.conversation"));assertEquals(1,count("SELECT count(*) FROM pf.task"));
    }
    @Test void preferenceCasRejectsConcurrentStaleAndInvalidFields() {
        assertEquals(0L,repository.preferences(alice).get("version"));
        repository.preferences(alice,0,java.util.Map.of("defaultCredentialSource","USER"));
        denied("PREFERENCE_VERSION_CONFLICT",()->repository.preferences(alice,0,java.util.Map.of("defaultCredentialSource","PLATFORM")));
        denied("UNSUPPORTED_PREFERENCE",()->repository.preferences(alice,1,java.util.Map.of("defaultPlanEnabled",true)));
        denied("INVALID_CREDENTIAL_SOURCE",()->repository.preferences(alice,1,java.util.Map.of("defaultCredentialSource","ANY")));
        assertEquals("USER",repository.preferences(alice).get("defaultCredentialSource"));assertEquals("PLATFORM",repository.preferences(bob).get("defaultCredentialSource"));
    }
    @Test void userKeyReplacementRetainsSnapshotAndNeverFallsBack() throws Exception {
        repository.bind(MODEL,bob,key("PLATFORM",null),"PLATFORM");
        var old=cipher.encrypt(UUID.randomUUID(),"USER",bob.spaceId(),bob.userId(),SENTINEL);
        repository.replaceUserCredential(bob,0,old);var first=repository.first(bob,UUID.randomUUID(),"User",MODEL,"USER","hi");
        var next=cipher.encrypt(UUID.randomUUID(),"USER",bob.spaceId(),bob.userId(),"different-sentinel");
        repository.replaceUserCredential(bob,1,next);var config=repository.configuration(bob,first.task().id());
        assertEquals(old.id().toString(),((java.util.Map<?,?>)config.get("values")).get("credentialId"));assertEquals(false,((java.util.Map<?,?>)config.get("restrictions")).get("credentialActive"));
        denied("CREDENTIAL_VERSION_CONFLICT",()->repository.replaceUserCredential(bob,1,null));
        denied("CREDENTIAL_SCOPE_REJECTED",()->repository.replaceUserCredential(alice,0,next));
        denied("RESOURCE_NOT_FOUND",()->repository.configuration(alice,first.task().id()));
        var lease=claim(first.task());denied("CREDENTIAL_REVOKED",()->repository.check(lease));
        repository.replaceUserCredential(bob,2,null);
        denied("CREDENTIAL_SOURCE_UNAVAILABLE",()->repository.first(bob,UUID.randomUUID(),"No fallback",MODEL,"USER","hi"));
        assertFalse(repository.credentialMetadata(bob).toString().contains(SENTINEL));
    }
    @Test void proRequestAndSettlementUseFrozenModelAndPrices() throws Exception {
        UUID pro=UUID.fromString("0199e000-0000-7000-8000-000000000003");
        sql("INSERT INTO pf.model_definition(id,model_name,endpoint_alias,input_micros_per_million,output_micros_per_million,pricing_version) VALUES('"+pro+"','deepseek-v4-pro','DEEPSEEK',9000000,27000000,'pro-2026-10-17-peak-bound')");
        repository.bind(pro,alice,key("PLATFORM",null),"PLATFORM");var first=repository.first(alice,UUID.randomUUID(),"Pro",pro,"PLATFORM","hi");
        sql("UPDATE pf.model_definition SET input_micros_per_million=1,output_micros_per_million=1,version=version+1 WHERE id='"+pro+"'");
        var lease=claim(first.task());assertEquals("deepseek-v4-pro",repository.modelName(lease));var call=repository.beginCall(lease,true);repository.startCall(lease,call,true);
        repository.settle(lease,call,new Round("ok",List.of(),"stop",100,10),"safe-id",null,true);
        assertEquals(1170,count("SELECT cost_micros FROM pf.model_call WHERE id='"+call.id()+"'"));assertEquals(1170,count("SELECT consumed_micros FROM pf.acceptance_model_meter"));
        repository.finish(lease,"SUCCEEDED",null);
    }
    @Test void requestTraceReachesOutboxAttemptCallAndPrivateFailureDiagnostics() throws Exception {
        repository.bind(MODEL,alice,key("PLATFORM",null),"PLATFORM");
        UUID trace=UUID.randomUUID();com.agentplatform.types.RequestTrace.enter(trace);
        com.agentplatform.domain.chat.ChatData.Task task;
        try { task=submit(alice); } finally {com.agentplatform.types.RequestTrace.clear();}
        var lease=claim(task);var call=repository.beginCall(lease,false);repository.startCall(lease,call,false);
        repository.delta(lease,"部分回复😀");
        repository.settle(lease,call,null,null,"PROVIDER_REQUEST_FAILED",false);
        repository.finish(lease,"FAILED","PROVIDER_REQUEST_FAILED");
        for(String table:List.of("task","event_outbox","attempt","model_call","failure_record"))
            assertEquals(1,count("SELECT count(*) FROM pf."+table+" WHERE trace_id='"+trace+"'"),table);
        var value=repository.diagnostics(alice,task.id());assertEquals(trace.toString(),value.get("traceId"));
        assertEquals("UNKNOWN_SIDE_EFFECT",value.get("classification"));assertEquals(true,value.get("hasPartialOutput"));
        assertEquals(false,value.get("automaticReplayAllowed"));assertFalse(value.toString().contains(SENTINEL));
        denied("RESOURCE_NOT_FOUND",()->repository.diagnostics(bob,task.id()));
    }
    @Test void successfulFinalCommitCannotBypassRevokedIdentity() throws Exception {
        repository.bind(MODEL,alice,key("PLATFORM",null),"PLATFORM");
        var lease=claim(submit(alice));repository.delta(lease,"partial");
        sql("UPDATE pf.membership SET status='REVOKED' WHERE user_id='"+alice.userId()+"'");
        denied("RESOURCE_NOT_FOUND",()->repository.finish(lease,"SUCCEEDED",null));
        repository.finish(lease,"FAILED","RESOURCE_NOT_FOUND");
        assertEquals(0,count("SELECT count(*) FROM pf.task WHERE status='SUCCEEDED'"));
    }

}
