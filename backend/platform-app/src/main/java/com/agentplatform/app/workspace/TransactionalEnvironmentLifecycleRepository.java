package com.agentplatform.app.workspace;

import com.agentplatform.domain.workspace.EnvironmentLifecycleRepository;
import com.agentplatform.domain.workspace.WorkspaceData.Actor;
import com.agentplatform.domain.workspace.WorkspaceFailure;
import java.util.UUID;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** One managed datasource/transaction manager; no independent lifecycle write path. */
public final class TransactionalEnvironmentLifecycleRepository implements EnvironmentLifecycleRepository {
    private final EnvironmentLifecycleRepository delegate;
    private final TransactionTemplate transactions;

    public TransactionalEnvironmentLifecycleRepository(EnvironmentLifecycleRepository delegate, TransactionTemplate transactions) {
        this.delegate = delegate; this.transactions = transactions;
    }

    @Override public boolean activatePending(UUID spaceId,UUID workspaceId) {
        try {return transactions.execute(status->delegate.activatePending(spaceId,workspaceId));}
        catch (DataAccessException | TransactionException unavailable) {
            var failure=new WorkspaceFailure(503,"ENVIRONMENT_STORAGE_UNAVAILABLE");
            failure.initCause(unavailable);throw failure;
        }
    }

    @Override public Admission admit(Actor actor, UUID workspaceId, UUID requestId, String hash,
            long version, Intent intent) {
        try {
            return transactions.execute(status -> delegate.admit(actor, workspaceId, requestId, hash, version, intent));
        } catch (DuplicateKeyException | ConcurrencyFailureException conflict) {
            throw new WorkspaceFailure(409, "ENVIRONMENT_CONCURRENT_CHANGE");
        } catch (DataAccessException | TransactionException unavailable) {
            var failure = new WorkspaceFailure(503, "ENVIRONMENT_STORAGE_UNAVAILABLE");
            failure.initCause(unavailable); throw failure;
        }
    }
}
