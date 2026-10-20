package com.agentplatform.app.chat;

import com.agentplatform.domain.chat.CancellationLedger.*;
import com.agentplatform.domain.chat.ChatData.Actor;
import com.agentplatform.domain.chat.ChatRepository;
import java.util.UUID;

/** User projection and protected operator mutation deliberately have separate entrypoints. */
public final class ReconciliationService {
    private final ChatRepository repository;
    public ReconciliationService(ChatRepository repository) { this.repository=repository; }
    public Reconciliation read(Actor actor,UUID task) { return repository.reconciliation(actor,task); }
    public Reconciliation inspect(UUID task) { return repository.reconciliationForOperator(task); }
    public void resolve(Command evidence) { repository.reconcile(evidence); }
}
