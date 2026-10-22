package com.agentplatform.app.workspace;

import com.agentplatform.domain.workspace.*;
import com.agentplatform.domain.workspace.EnvironmentExecutionData.*;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.*;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.*;

public final class TransactionalEnvironmentExecutionRepository implements EnvironmentExecutionRepository {
    private final EnvironmentExecutionRepository delegate;
    private final TransactionTemplate transactions;
    public TransactionalEnvironmentExecutionRepository(EnvironmentExecutionRepository delegate,TransactionTemplate transactions) {
        this.delegate=delegate;this.transactions=transactions;
    }
    private void beforeCommit(Command command) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void beforeCommit(boolean readOnly) {delegate.checkOwnership(command);}
        });
    }
    private <T> T tx(Command guard,Supplier<T> action) {
        try {return transactions.execute(status->{var result=action.get();if(guard!=null)beforeCommit(guard);return result;});}
        catch(DuplicateKeyException|ConcurrencyFailureException conflict) {throw new WorkspaceFailure(409,"ENVIRONMENT_CONCURRENT_CHANGE");}
        catch(DataAccessException|TransactionException unavailable) {
            var failure=new WorkspaceFailure(503,"ENVIRONMENT_STORAGE_UNAVAILABLE");failure.initCause(unavailable);throw failure;
        }
    }
    @Override public List<Publication> publications(UUID owner) {return tx(null,()->delegate.publications(owner));}
    @Override public void published(Publication item,boolean confirmed) {tx(null,()->{delegate.published(item,confirmed);return null;});}
    @Override public Command claim(Publication item,UUID owner) {return tx(null,()->{
        var command=delegate.claim(item,owner);if(command!=null)beforeCommit(command);return command;
    });}
    @Override public Command lookup(UUID id) {return tx(null,()->delegate.lookup(id));}
    @Override public void check(Command command) {tx(null,()->{delegate.check(command);return null;});}
    @Override public void checkOwnership(Command command) {tx(null,()->{delegate.checkOwnership(command);return null;});}
    @Override public void renew(Command command) {tx(command,()->{delegate.renew(command);return null;});}
    @Override public void beforeCall(Command command) {tx(command,()->{delegate.beforeCall(command);return null;});}
    @Override public Receipt accept(Command command,String hash) {return tx(command,()->delegate.accept(command,hash));}
    @Override public Receipt receipt(Command command) {return tx(null,()->delegate.receipt(command));}
    @Override public void commitReceipt(Command command,Receipt receipt) {tx(command,()->{delegate.commitReceipt(command,receipt);return null;});}
    @Override public void complete(Command command,Receipt receipt) {tx(command,()->{delegate.complete(command,receipt);return null;});}
    @Override public void uncertain(Command command,String code) {tx(command,()->{delegate.uncertain(command,code);return null;});}
    @Override public void quarantine(UUID id,String reason,String hash) {tx(null,()->{delegate.quarantine(id,reason,hash);return null;});}
}
