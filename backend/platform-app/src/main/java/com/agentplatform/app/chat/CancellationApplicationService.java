package com.agentplatform.app.chat;

import com.agentplatform.domain.chat.ChatData.*;
import com.agentplatform.domain.chat.ChatRepository;
import java.util.UUID;

/** User intent is committed before any asynchronous execution teardown. */
public final class CancellationApplicationService {
    private final ChatRepository repository;
    public CancellationApplicationService(ChatRepository repository) { this.repository=repository; }
    public Task cancel(Actor actor,UUID task,UUID command) { return repository.cancel(actor,task,command); }
}
