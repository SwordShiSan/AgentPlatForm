package com.agentplatform.app.workspace;

import com.agentplatform.domain.workspace.*;
import com.agentplatform.domain.workspace.EnvironmentUseData.*;
import com.agentplatform.domain.workspace.WorkspaceData.Actor;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.*;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.*;

/** Short fact transactions; guards recheck database time immediately before commit. */
public final class TransactionalEnvironmentUseRepository implements EnvironmentUseRepository {
    private final EnvironmentUseRepository delegate;
    private final TransactionTemplate transactions;
    public TransactionalEnvironmentUseRepository(EnvironmentUseRepository delegate,TransactionTemplate transactions) {
        this.delegate=delegate;this.transactions=transactions;
    }
    private <T> T tx(Runnable guard,Supplier<T> action) {
        try {return transactions.execute(status->{
            var result=action.get();
            if(guard!=null)TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) {guard.run();}
            });
            return result;
        });}
        catch(DuplicateKeyException|ConcurrencyFailureException conflict) {throw new WorkspaceFailure(409,"ENVIRONMENT_USE_CONCURRENT_CHANGE");}
        catch(DataAccessException|TransactionException unavailable) {
            var failure=new WorkspaceFailure(503,"ENVIRONMENT_STORAGE_UNAVAILABLE");failure.initCause(unavailable);throw failure;
        }
    }
    @Override public Admission admit(Actor actor,UUID workspace,UUID request,UUID owner,Request body) {
        return tx(null,()->{var admitted=delegate.admit(actor,workspace,request,owner,body);
            if(admitted.fresh())TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) {delegate.checkOwnership(admitted.command());}
            });return admitted;});
    }
    @Override public Command lookup(UUID id) {return tx(null,()->delegate.lookup(id));}
    @Override public Command lookup(Actor actor,UUID id) {return tx(null,()->delegate.lookup(actor,id));}
    @Override public void checkOwnership(Command command) {tx(null,()->{delegate.checkOwnership(command);return null;});}
    @Override public void check(Command command,UUID runner) {tx(null,()->{delegate.check(command,runner);return null;});}
    @Override public void checkStopObservation(Command command,UUID runner) {tx(null,()->{delegate.checkStopObservation(command,runner);return null;});}
    @Override public void checkCompletion(Command command) {tx(null,()->{delegate.checkCompletion(command);return null;});}
    @Override public void renew(Command command) {tx(()->delegate.checkOwnership(command),()->{delegate.renew(command);return null;});}
    @Override public boolean accept(Command command,UUID runner) {return tx(()->delegate.check(command,runner),()->delegate.accept(command,runner));}
    @Override public boolean cancelled(Command command) {return tx(null,()->delegate.cancelled(command));}
    @Override public void cancel(Actor actor,UUID id) {tx(null,()->{delegate.cancel(actor,id);return null;});}
    @Override public boolean stopCommitted(UUID id) {return tx(null,()->delegate.stopCommitted(id));}
    @Override public void recordExec(Command command,UUID runner,String container,String exec) {
        tx(()->delegate.check(command,runner),()->{delegate.recordExec(command,runner,container,exec);return null;});
    }
    @Override public void commitFact(Command command,UUID runner,Fact fact) {
        Runnable guard=fact!=null&&fact.stop()!=null&&"UNKNOWN".equals(fact.resultState())?
                ()->delegate.checkStopObservation(command,runner):()->delegate.check(command,runner);
        tx(guard,()->{delegate.commitFact(command,runner,fact);return null;});
    }
    @Override public Receipt receipt(Command command) {return tx(null,()->delegate.receipt(command));}
    @Override public Fact committedFact(Command command) {return tx(null,()->delegate.committedFact(command));}
    @Override public Fact result(Actor actor,UUID id) {return tx(null,()->delegate.result(actor,id));}
    @Override public void complete(Command command) {tx(()->delegate.checkCompletion(command),()->{delegate.complete(command);return null;});}
    @Override public void uncertain(Command command,String code) {tx(()->delegate.checkOwnership(command),()->{delegate.uncertain(command,code);return null;});}
}
