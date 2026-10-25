package com.agentplatform.app.files;

import com.agentplatform.domain.files.*;
import com.agentplatform.domain.files.FileData.*;
import com.agentplatform.domain.workspace.RunnerInstance;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

public final class TransactionalFileRunnerRepository implements FileRunnerRepository {
    private final FileRunnerRepository delegate;
    private final TransactionTemplate transactions;
    public TransactionalFileRunnerRepository(FileRunnerRepository delegate,TransactionTemplate transactions){this.delegate=delegate;this.transactions=transactions;}
    private <T> T tx(Supplier<T> action){
        try{return transactions.execute(status->action.get());}
        catch(DataAccessException|TransactionException unavailable){var failure=new FileFailure(503,"FILE_RUNNER_STORAGE_UNAVAILABLE");failure.initCause(unavailable);throw failure;}
    }
    @Override public void register(RunnerInstance i){tx(()->{delegate.register(i);return null;});}
    @Override public Command lookup(UUID s,UUID w,UUID o){return tx(()->delegate.lookup(s,w,o));}
    @Override public boolean accept(Command c,RunnerInstance i,String h){return tx(()->delegate.accept(c,i,h));}
    @Override public void guard(Command c,UUID i){tx(()->{delegate.guard(c,i);return null;});}
    @Override public FileRuntime.Physical physical(Command c){return tx(()->delegate.physical(c));}
    @Override public void created(Command c,UUID i,String id){tx(()->{delegate.created(c,i,id);return null;});}
    @Override public void startRequested(Command c,UUID i){tx(()->{delegate.startRequested(c,i);return null;});}
    @Override public void started(Command c,UUID i,String time){tx(()->{delegate.started(c,i,time);return null;});}
    @Override public void exec(Command c,UUID i,String id){tx(()->{delegate.exec(c,i,id);return null;});}
    @Override public boolean priorRunnerStopped(Command c,UUID i){return tx(()->delegate.priorRunnerStopped(c,i));}
    @Override public boolean mayObserve(Command c,UUID i){return tx(()->delegate.mayObserve(c,i));}
    @Override public Receipt receipt(Command c){return tx(()->delegate.receipt(c));}
    @Override public Receipt observeNeverAccepted(Command c,UUID i,String h){return tx(()->delegate.observeNeverAccepted(c,i,h));}
    @Override public void recordResult(Command c,UUID i,FileRuntime.Result result){tx(()->{delegate.recordResult(c,i,result);return null;});}
}
