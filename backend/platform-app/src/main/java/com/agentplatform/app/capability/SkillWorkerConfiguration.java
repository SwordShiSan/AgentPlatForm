package com.agentplatform.app.capability;

import com.agentplatform.domain.capability.SkillValidationRepository;
import com.agentplatform.domain.files.AssetStore;
import com.agentplatform.infrastructure.capability.SkillArchiveValidator;
import com.agentplatform.infrastructure.capability.SpringSkillMessaging;
import com.agentplatform.infrastructure.dao.capability.SkillWorkerMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@Profile("workspace-worker")
@ConditionalOnProperty(name = "platform.capabilities.enabled", havingValue = "true")
public class SkillWorkerConfiguration {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SkillWorkerConfiguration.class);
    @Bean SkillValidationRepository skillValidationRepository(SkillWorkerMapper mapper, TransactionTemplate transactions) {
        return new TransactionalSkillValidationRepository(mapper, transactions);
    }
    @Bean ThreadPoolTaskScheduler skillTimers() {
        var timers = new ThreadPoolTaskScheduler(); timers.setPoolSize(2); timers.setThreadNamePrefix("skill-import-");
        timers.setRemoveOnCancelPolicy(true); timers.setWaitForTasksToCompleteOnShutdown(false); return timers;
    }
    @Bean Declarables skillImportTopology() {
        var exchange = new DirectExchange(SpringSkillMessaging.EXCHANGE, true, false);
        var queue = QueueBuilder.durable(SpringSkillMessaging.QUEUE).quorum().build();
        return new Declarables(exchange, queue, BindingBuilder.bind(queue).to(exchange).with(SpringSkillMessaging.ROUTE));
    }
    @Bean(destroyMethod = "close") SpringSkillMessaging skillMessaging(CachingConnectionFactory connections, SkillValidationRepository repository) {
        return new SpringSkillMessaging(connections, repository);
    }
    @Bean SkillValidationExecution skillValidationExecution(SkillValidationRepository repository, AssetStore assets,
            @Qualifier("skillTimers") ThreadPoolTaskScheduler timers,
            @Value("${platform.capabilities.import-temp-dir:${java.io.tmpdir}}") String tempDirectory) {
        return new SkillValidationExecution(repository, new SkillValidationService(repository, assets,
                new SkillArchiveValidator(), Path.of(tempDirectory)), timers.getScheduledExecutor());
    }
    @Bean SmartLifecycle skillImportLifecycle(SkillValidationRepository repository, SpringSkillMessaging messaging,
            SkillValidationExecution execution, @Qualifier("skillTimers") ThreadPoolTaskScheduler timers) {
        return new SmartLifecycle() {
            private volatile boolean running;
            private java.util.concurrent.ScheduledFuture<?> relay;
            private final UUID owner = UUID.randomUUID();
            @Override public void start() {
                running = true;
                messaging.start(execution::run);
                relay = timers.scheduleWithFixedDelay(() -> {
                    if (!running) return;
                    try {
                        var item = repository.nextPublication(owner);
                        if (item != null) { messaging.publish(item); repository.published(item); }
                    } catch (RuntimeException unavailable) {
                        LOG.debug("Skill import relay will retry after its lease");
                    }
                }, Duration.ofMillis(500));
            }
            @Override public void stop() { running = false; if (relay != null) relay.cancel(false); messaging.close(); }
            @Override public boolean isRunning() { return running; }
        };
    }
}
