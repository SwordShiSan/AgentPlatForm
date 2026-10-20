package com.agentplatform.app.chat;

import com.agentplatform.domain.chat.ChatFailure;
import com.agentplatform.domain.chat.ChatRepository;
import com.agentplatform.domain.chat.DispatchTransport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile({"chat-local", "chat-worker"})
public class ChatSchedulingConfiguration {
    @Bean(name = "taskScheduler")
    ThreadPoolTaskScheduler taskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("platform-control-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }

    @Bean @Profile("chat-worker")
    ThreadPoolTaskScheduler executionTimers() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("platform-execution-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }

    @Bean @Profile("chat-local")
    OutboxRelay outboxRelay(ChatRepository repository, DispatchTransport transport) { return new OutboxRelay(repository, transport); }

    @Bean @Profile("chat-worker")
    WorkerSupervisor workerSupervisor(ChatRepository repository, DispatchTransport transport, ChatExecution execution) {
        return new WorkerSupervisor(repository, transport, execution);
    }

    public static final class OutboxRelay {
        private final ChatRepository repository;
        private final DispatchTransport transport;
        private final UUID relay = UUID.randomUUID();
        OutboxRelay(ChatRepository repository, DispatchTransport transport) { this.repository = repository; this.transport = transport; }
        @Scheduled(fixedDelay = 500)
        public void relay() {
            try {
                repository.wakeDeferred();
                for (var publication : repository.publications(relay)) {
                    boolean confirmed = false;
                    try { transport.publish(publication.envelope()); confirmed = true; } catch (ChatFailure unavailable) { /* retain outbox retry */ }
                    repository.published(relay, publication, confirmed);
                }
            } catch (RuntimeException unavailable) {
                System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING, "Outbox relay waiting for dependency");
            }
        }
    }

    public static final class WorkerSupervisor implements DisposableBean {
        private final Path marker;
        private final Path instance;
        private final ChatRepository repository;
        private final DispatchTransport transport;
        private final ChatExecution execution;
        private final UUID owner = UUID.randomUUID();
        WorkerSupervisor(ChatRepository repository, DispatchTransport transport, ChatExecution execution) {
            this(repository,transport,execution,Path.of("/tmp/chat-worker-ready"),Path.of("/tmp/chat-worker-instance"));
        }
        WorkerSupervisor(ChatRepository repository, DispatchTransport transport, ChatExecution execution,Path marker,Path instance) {
            this.repository = repository; this.transport = transport; this.execution = execution;
            this.marker=marker;this.instance=instance;
        }
        @Scheduled(fixedDelay = 2000)
        public void supervise() {
            try {
                // Instance identity survives readiness withdrawal; it is never a readiness or stop proof.
                Files.writeString(instance, owner.toString());
                if (!execution.accepting() || !repository.ready() || !transport.connected()) throw new ChatFailure(503, "CHAT_STORAGE_UNAVAILABLE");
                transport.consume(delivery -> ChatConfiguration.handoff(repository, execution, owner, delivery));
                Files.writeString(marker, owner.toString());
            } catch (Exception unavailable) { withdraw(); }
        }
        private void withdraw() { try { Files.deleteIfExists(marker); } catch (Exception ignored) { /* readiness fails closed */ } }
        @Override public void destroy() { withdraw(); }
    }
}
