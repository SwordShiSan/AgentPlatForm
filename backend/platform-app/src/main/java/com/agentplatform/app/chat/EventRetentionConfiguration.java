package com.agentplatform.app.chat;

import com.agentplatform.infrastructure.dao.chat.EventMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@Profile("chat-local")
public class EventRetentionConfiguration {
    @Bean
    @ConditionalOnProperty(name = "platform.chat.retention.enabled", havingValue = "true", matchIfMissing = true)
    org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler eventRetentionScheduler() {
        var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("event-retention-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }
    @Bean
    @ConditionalOnProperty(name = "platform.chat.retention.enabled", havingValue = "true", matchIfMissing = true)
    EventRetentionService eventRetentionService(EventMapper events, TransactionTemplate transactions) {
        return new EventRetentionService(events, transactions);
    }
}
