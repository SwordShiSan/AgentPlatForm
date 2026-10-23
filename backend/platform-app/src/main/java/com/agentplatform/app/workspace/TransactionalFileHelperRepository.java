package com.agentplatform.app.workspace;

import com.agentplatform.domain.workspace.*;
import com.agentplatform.domain.workspace.EnvironmentUseData.Command;
import com.agentplatform.domain.workspace.FileHelperData.*;
import java.util.UUID;
import org.springframework.dao.*;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** Keeps Docker-independent helper facts in short Spring-managed transactions. */
public final class TransactionalFileHelperRepository implements FileHelperRepository {
    private final FileHelperRepository delegate;
    private final TransactionTemplate transactions;
    public TransactionalFileHelperRepository(FileHelperRepository delegate,TransactionTemplate transactions) {
        this.delegate=delegate;this.transactions=transactions;
    }
    private <T> T tx(java.util.function.Supplier<T> action) {
        try {return transactions.execute(status->action.get());}
        catch(DuplicateKeyException|ConcurrencyFailureException conflict) {throw new WorkspaceFailure(409,"FILE_HELPER_CONCURRENT_CHANGE");}
        catch(DataAccessException|TransactionException unavailable) {
            var failure=new WorkspaceFailure(503,"ENVIRONMENT_STORAGE_UNAVAILABLE");failure.initCause(unavailable);throw failure;
        }
    }
    private void tx(Runnable action) {tx(()->{action.run();return null;});}
    @Override public void intent(Command command,UUID runner,Intent intent) {tx(()->delegate.intent(command,runner,intent));}
    @Override public Snapshot find(Command command,UUID observer) {return tx(()->delegate.find(command,observer));}
    @Override public void created(Command command,UUID runner,Physical physical) {tx(()->delegate.created(command,runner,physical));}
    @Override public void requestStart(Command command,UUID runner) {tx(()->delegate.requestStart(command,runner));}
    @Override public void started(Command command,UUID runner,Physical physical) {tx(()->delegate.started(command,runner,physical));}
    @Override public void recordExec(Command command,UUID runner,Physical physical) {tx(()->delegate.recordExec(command,runner,physical));}
    @Override public void stopped(Command command,UUID observer,Stopped stop) {tx(()->delegate.stopped(command,observer,stop));}
    @Override public Stopped retirement(Command command) {return tx(()->delegate.retirement(command));}
}
