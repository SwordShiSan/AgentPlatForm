package com.agentplatform.app.preview;

import com.agentplatform.domain.files.FileData.Actor;
import com.agentplatform.domain.files.FileFailure;
import com.agentplatform.domain.preview.*;
import com.agentplatform.domain.preview.PreviewData.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.*;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** One Spring-managed transaction per fact boundary; never wraps HTTP/Docker/S3. */
public final class TransactionalPreviewRepository implements PreviewRepository {
    private final PreviewRepository delegate;private final TransactionTemplate transactions;
    public TransactionalPreviewRepository(PreviewRepository delegate,TransactionTemplate transactions){this.delegate=delegate;this.transactions=transactions;}
    private <T>T tx(Supplier<T> action){try{return transactions.execute(status->action.get());}catch(DuplicateKeyException|ConcurrencyFailureException conflict){throw new FileFailure(409,"PREVIEW_CONCURRENT_CHANGE");}catch(DataAccessException|TransactionException unavailable){var f=new FileFailure(503,"PREVIEW_STORAGE_UNAVAILABLE");f.initCause(unavailable);throw f;}}
    private void run(Runnable action){tx(()->{action.run();return null;});}
    @Override public Job create(Actor a,UUID f,UUID v,String format,UUID request,String hash){return tx(()->delegate.create(a,f,v,format,request,hash));}
    @Override public Job get(Actor a,UUID p){return tx(()->delegate.get(a,p));}
    @Override public Job retry(Actor a,UUID p,long version,UUID request,String hash){return tx(()->delegate.retry(a,p,version,request,hash));}
    @Override public List<Publication> publications(UUID owner){return tx(()->delegate.publications(owner));}
    @Override public void published(Publication item,boolean confirmed){run(()->delegate.published(item,confirmed));}
    @Override public Command claim(Publication item,UUID owner){return tx(()->delegate.claim(item,owner));}
    @Override public void guard(Command c){run(()->delegate.guard(c));}
    @Override public void renew(Command c){run(()->delegate.renew(c));}
    @Override public void beforeCall(Command c){run(()->delegate.beforeCall(c));}
    @Override public void complete(Command c,Receipt r){run(()->delegate.complete(c,r));}
    @Override public void unknown(Command c,String code){run(()->delegate.unknown(c,code));}
    @Override public void blockExpired(){run(delegate::blockExpired);}
    @Override public List<Command> reconciliations(){return tx(delegate::reconciliations);}
    @Override public void quarantine(UUID id,String hash){run(()->delegate.quarantine(id,hash));}
    @Override public boolean runnerClaim(Command c,UUID runner){return tx(()->delegate.runnerClaim(c,runner));}
    @Override public boolean runnerObserveNeverAccepted(Command c,UUID runner){return tx(()->delegate.runnerObserveNeverAccepted(c,runner));}
    @Override public Command runnerLookup(UUID id){return tx(()->delegate.runnerLookup(id));}
    @Override public PreviewRuntime.Physical runnerPhysical(Command c,UUID runner){return tx(()->delegate.runnerPhysical(c,runner));}
    @Override public boolean runnerStartWasRequested(Command c,UUID runner){return tx(()->delegate.runnerStartWasRequested(c,runner));}
    @Override public void runnerCreated(Command c,UUID runner,String id){run(()->delegate.runnerCreated(c,runner,id));}
    @Override public void runnerStartRequested(Command c,UUID runner){run(()->delegate.runnerStartRequested(c,runner));}
    @Override public void runnerStarted(Command c,UUID runner,String at){run(()->delegate.runnerStarted(c,runner,at));}
    @Override public void runnerObservedPhysical(Command c,UUID runner,String id,String at,boolean requested){run(()->delegate.runnerObservedPhysical(c,runner,id,at,requested));}
    @Override public void runnerExecCreated(Command c,UUID runner,String id){run(()->delegate.runnerExecCreated(c,runner,id));}
    @Override public void runnerGuard(Command c,UUID runner){run(()->delegate.runnerGuard(c,runner));}
    @Override public void runnerReceipt(Command c,UUID runner,Receipt r){run(()->delegate.runnerReceipt(c,runner,r));}
    @Override public Receipt receipt(Command c){return tx(()->delegate.receipt(c));}
    @Override public void recordSourceTransferClosed(Command c,UUID runner){run(()->delegate.recordSourceTransferClosed(c,runner));}
}
