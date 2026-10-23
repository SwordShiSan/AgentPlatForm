package com.agentplatform.app.workspace;

import com.agentplatform.domain.workspace.*;
import java.util.function.IntSupplier;
import org.springframework.dao.*;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

public final class TransactionalEnvironmentMaintenanceRepository implements EnvironmentMaintenanceRepository {
    private final EnvironmentMaintenanceRepository delegate;
    private final TransactionTemplate transactions;
    public TransactionalEnvironmentMaintenanceRepository(EnvironmentMaintenanceRepository delegate,TransactionTemplate transactions) {
        this.delegate=delegate;this.transactions=transactions;
    }
    private int tx(IntSupplier action) {
        try {return transactions.execute(status->action.getAsInt());}
        catch(ConcurrencyFailureException conflict) {throw new WorkspaceFailure(409,"ENVIRONMENT_CONCURRENT_CHANGE");}
        catch(DataAccessException|TransactionException unavailable) {
            var failure=new WorkspaceFailure(503,"ENVIRONMENT_STORAGE_UNAVAILABLE");failure.initCause(unavailable);throw failure;
        }
    }
    @Override public int blockExpired() {return tx(delegate::blockExpired);}
    @Override public int scheduleStop() {return tx(delegate::scheduleStop);}
}
