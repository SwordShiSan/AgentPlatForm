package com.agentplatform.app.workspace;

import com.agentplatform.domain.workspace.*;
import org.springframework.dao.*;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

public final class TransactionalRunnerStopProofRepository implements RunnerStopProofRepository {
    private final RunnerStopProofRepository delegate;
    private final TransactionTemplate transactions;
    public TransactionalRunnerStopProofRepository(RunnerStopProofRepository delegate,TransactionTemplate transactions) {
        this.delegate=delegate;this.transactions=transactions;
    }
    @Override public void record(StoppedRunner proof) {
        try {transactions.executeWithoutResult(status->delegate.record(proof));}
        catch(ConcurrencyFailureException|DuplicateKeyException conflict) {throw new WorkspaceFailure(409,"RUNNER_PROOF_CONCURRENT_CHANGE");}
        catch(DataAccessException|TransactionException unavailable) {
            var failure=new WorkspaceFailure(503,"ENVIRONMENT_STORAGE_UNAVAILABLE");failure.initCause(unavailable);throw failure;
        }
    }
}
