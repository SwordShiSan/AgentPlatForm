package com.agentplatform.app.chat;

import com.agentplatform.domain.chat.ChatData.Actor;
import com.agentplatform.domain.chat.ChatData.Event;
import com.agentplatform.domain.chat.ChatData.Snapshot;
import com.agentplatform.domain.chat.ChatRepository;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import com.agentplatform.domain.chat.ChatFailure;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Authorization, replay bounds and projection rows share one MVCC view. */
public final class SnapshotQueryService {
    private final ChatRepository repository;
    private final TransactionTemplate reads;

    public SnapshotQueryService(ChatRepository repository, PlatformTransactionManager transactions) {
        this.repository = repository;
        reads = new TransactionTemplate(transactions);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        // The existing authorization function takes FOR SHARE locks. PostgreSQL
        // prohibits those in a read-only transaction; RR still provides the read view.
    }

    public Snapshot snapshot(Actor actor, UUID conversation) {
        return read(() -> repository.snapshot(actor, conversation));
    }

    public List<Event> events(Actor actor, UUID conversation, long after) {
        return read(() -> repository.events(actor, conversation, after));
    }

    private <T> T read(Supplier<T> query) {
        try { return reads.execute(status -> query.get()); }
        catch (DataAccessException | TransactionException unavailable) {
            var failure = new ChatFailure(503, "CHAT_STORAGE_UNAVAILABLE");
            failure.initCause(unavailable);
            throw failure;
        }
    }
}
