package com.agentplatform.app.chat;

import com.agentplatform.domain.chat.ChatRepository;
import com.agentplatform.domain.chat.ChatData.Actor;
import com.agentplatform.domain.chat.RecoveryLedger.*;
import java.util.Objects;
import java.util.UUID;

/** Operator commands and read projection share the repository's transactional authority. */
public final class CheckpointRestoreService {
    private final ChatRepository repository;
    public CheckpointRestoreService(ChatRepository repository) { this.repository=Objects.requireNonNull(repository); }
    public Recovery read(Actor actor,UUID task) { return repository.recovery(actor,task); }
    public Recovery inspect(UUID task) { return repository.recoveryForOperator(task); }
    public Recovery restore(RestoreCommand command) { return repository.restore(command); }
}
