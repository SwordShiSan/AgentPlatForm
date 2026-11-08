package com.agentplatform.app.capability;

import static org.junit.jupiter.api.Assertions.*;
import com.agentplatform.domain.capability.SkillValidationRepository;
import com.agentplatform.domain.files.FileFailure;
import com.agentplatform.infrastructure.capability.SpringSkillMessaging;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

/** Real broker transport; the claim ledger is a deterministic test double. */
class SkillMessagingRabbitTest {
    static final class Repository implements SkillValidationRepository {
        final AtomicInteger attempts = new AtomicInteger(); final AtomicBoolean claimed = new AtomicBoolean();
        final CountDownLatch duplicateSeen = new CountDownLatch(1);
        @Override public Command claim(Publication p, UUID owner) {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) throw new FileFailure(503, "FIXTURE_CLAIM_FAILED");
            if (!claimed.compareAndSet(false, true)) { duplicateSeen.countDown(); return null; }
            return new Command(p.spaceId(), UUID.randomUUID(), p.importId(), owner, p.generation(), 1, "fixture", 0, "a".repeat(64));
        }
        @Override public void guard(Command c) { throw new UnsupportedOperationException(); }
        @Override public void draft(Command c, Manifest m) { throw new UnsupportedOperationException(); }
        @Override public void rejected(Command c, String r) { throw new UnsupportedOperationException(); }
        @Override public void unknown(Command c, String r) { throw new UnsupportedOperationException(); }
        @Override public Publication nextPublication(UUID o) { throw new UnsupportedOperationException(); }
        @Override public void published(Publication p) { throw new UnsupportedOperationException(); }
        @Override public void quarantine(UUID id, String hash) { fail("valid message must not be quarantined"); }
    }
    @Test void failedClaimRedeliversAndDuplicateDoesNotExecuteTwice() throws Exception {
        String rawPort = System.getenv("R62_AMQP_PORT"); Assumptions.assumeTrue(rawPort != null, "R62 isolated broker NOT_RUN");
        assertEquals("1", System.getenv("R62_AMQP_ACCEPTANCE"));
        String password = Files.readString(Path.of(System.getenv("R62_AMQP_PASSWORD_FILE"))).strip();
        var connection = new CachingConnectionFactory("127.0.0.1", Integer.parseInt(rawPort));
        connection.setUsername("r62fixture"); connection.setPassword(password);
        connection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED); connection.setPublisherReturns(true);
        var admin = new RabbitAdmin(connection); var exchange = new DirectExchange(SpringSkillMessaging.EXCHANGE, true, false);
        var queue = QueueBuilder.durable(SpringSkillMessaging.QUEUE).quorum().build();
        admin.declareExchange(exchange); admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with(SpringSkillMessaging.ROUTE));
        var repository = new Repository(); var executions = new AtomicInteger(); var completed = new CountDownLatch(1);
        try (var messaging = new SpringSkillMessaging(connection, repository)) {
            messaging.start(command -> { executions.incrementAndGet(); completed.countDown(); });
            var item = new SkillValidationRepository.Publication(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID());
            messaging.publish(item);
            assertTrue(completed.await(30, TimeUnit.SECONDS), "broker must redeliver after failed claim");
            messaging.publish(item);
            assertTrue(repository.duplicateSeen.await(20, TimeUnit.SECONDS));
            assertEquals(1, executions.get()); assertTrue(repository.attempts.get() >= 3);
        } finally { admin.deleteQueue(SpringSkillMessaging.QUEUE); connection.destroy(); }
    }
}
