package com.agentplatform.app.workspace;

import com.agentplatform.domain.workspace.EnvironmentLifecycleRepository;
import com.agentplatform.domain.workspace.WorkspaceData.Actor;
import com.agentplatform.domain.workspace.WorkspaceFailure;
import java.util.UUID;
import java.util.function.Supplier;
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
        return tx(()->delegate.activatePending(spaceId,workspaceId));
    }

    @Override public Snapshot get(Actor actor,UUID workspaceId) {return tx(()->delegate.get(actor,workspaceId));}
    @Override public boolean activateNext() {return tx(delegate::activateNext);}
    @Override public Operation operation(Actor actor,UUID workspaceId,UUID operationId) {
        return tx(()->delegate.operation(actor,workspaceId,operationId));
    }
    @Override public Operation request(Actor actor,UUID workspaceId,UUID requestId) {
        return tx(()->delegate.request(actor,workspaceId,requestId));
    }

    @Override public Submission prepare(Actor actor,UUID workspaceId,UUID requestId,String hash,long version,boolean retry) {
        return tx(()->delegate.prepare(actor,workspaceId,requestId,hash,version,retry));
    }

    @Override public Admission admit(Actor actor, UUID workspaceId, UUID requestId, String hash,
            long version, Intent intent) {
        return tx(()->delegate.admit(actor,workspaceId,requestId,hash,version,intent));
    }

    private <T> T tx(Supplier<T> action) {
        try {
            return transactions.execute(status -> action.get());
        } catch (DuplicateKeyException | ConcurrencyFailureException conflict) {
            throw new WorkspaceFailure(409, "ENVIRONMENT_CONCURRENT_CHANGE");
        } catch (DataAccessException | TransactionException unavailable) {
            var failure = new WorkspaceFailure(503, "ENVIRONMENT_STORAGE_UNAVAILABLE");
            failure.initCause(unavailable); throw failure;
        }
    }
}
