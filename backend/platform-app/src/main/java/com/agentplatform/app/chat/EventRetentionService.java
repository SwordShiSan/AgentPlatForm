package com.agentplatform.app.chat;

import com.agentplatform.infrastructure.dao.chat.EventMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

/** Each prefix is committed independently; a failed round never advances its watermark. */
public final class EventRetentionService {
    private final EventMapper events;
    private final TransactionTemplate transactions;

    public EventRetentionService(EventMapper events, TransactionTemplate transactions) {
        this.events = events;
        this.transactions = transactions;
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000, scheduler = "eventRetentionScheduler")
    public void collect() {
        var candidates = transactions.execute(status -> events.retentionCandidates());
        if (candidates == null) return;
        for (var conversation : candidates) {
            transactions.executeWithoutResult(status -> events.collectExpiredPrefix(conversation));
        }
    }
}
