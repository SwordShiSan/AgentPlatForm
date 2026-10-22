package com.agentplatform.app.workspace;

import com.agentplatform.domain.workspace.EnvironmentPreparationRepository;
import com.agentplatform.domain.workspace.WorkspaceData.Actor;
import com.agentplatform.domain.workspace.WorkspaceFailure;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** Reuses the process's managed datasource and transaction manager. */
public final class TransactionalEnvironmentPreparationRepository implements EnvironmentPreparationRepository {
    private final EnvironmentPreparationRepository delegate;
    private final TransactionTemplate transactions;
    public TransactionalEnvironmentPreparationRepository(EnvironmentPreparationRepository delegate, TransactionTemplate transactions) {
        this.delegate = delegate; this.transactions = transactions;
    }
    private <T> T tx(Supplier<T> action) {
        try { return transactions.execute(status -> action.get()); }
        catch (DuplicateKeyException | ConcurrencyFailureException conflict) {
            throw new WorkspaceFailure(409, "ENVIRONMENT_CONCURRENT_CHANGE");
        } catch (DataAccessException | TransactionException unavailable) {
            var failure = new WorkspaceFailure(503, "ENVIRONMENT_STORAGE_UNAVAILABLE"); failure.initCause(unavailable); throw failure;
        }
    }
    @Override public Snapshot get(Actor actor, UUID workspaceId) { return tx(() -> delegate.get(actor, workspaceId)); }
    @Override public Submission prepare(Actor actor, UUID workspaceId, UUID request, String hash, long version, boolean retry) {
        return tx(() -> delegate.prepare(actor, workspaceId, request, hash, version, retry));
    }
}
