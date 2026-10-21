package com.agentplatform.app.workspace;

import com.agentplatform.domain.workspace.*;
import com.agentplatform.domain.workspace.WorkspaceData.*;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.*;

public final class TransactionalWorkspaceRepository implements WorkspaceRepository {
    private final WorkspaceRepository delegate;
    private final TransactionTemplate transactions;
    private final WorkspaceFixtureBarrier barrier;
    public TransactionalWorkspaceRepository(WorkspaceRepository delegate, TransactionTemplate transactions) {
        this(delegate,transactions,WorkspaceFixtureBarrier.none());
    }
    TransactionalWorkspaceRepository(WorkspaceRepository delegate,TransactionTemplate transactions,WorkspaceFixtureBarrier barrier) {
        this.delegate=delegate;this.transactions=transactions;this.barrier=barrier;
    }
    private <T> T tx(Supplier<T> action) { return guarded(null, false, action); }
    private <T> T guarded(Command command, boolean authorization, Supplier<T> action) {
        try {
            return transactions.execute(status -> {
                T result = action.get();
                if (command != null) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void beforeCommit(boolean readOnly) {
                        if (authorization) delegate.check(command); else delegate.checkOwnership(command);
                    }
                });
                return result;
            });
        } catch (DuplicateKeyException | ConcurrencyFailureException conflict) {
            throw new WorkspaceFailure(409, "WORKSPACE_CONCURRENT_CHANGE");
        } catch (DataAccessException | TransactionException unavailable) {
            var failure = new WorkspaceFailure(503, "WORKSPACE_STORAGE_UNAVAILABLE"); failure.initCause(unavailable); throw failure;
        }
    }
    @Override public Workspace create(Actor a, UUID key, String hash, String name) { return tx(() -> delegate.create(a,key,hash,name)); }
    @Override public Workspace get(Actor a, UUID id) { return tx(() -> delegate.get(a,id)); }
    @Override public List<Workspace> list(Actor a, UUID before, int limit) { return tx(() -> delegate.list(a,before,limit)); }
    @Override public Workspace retry(Actor a, UUID id, UUID key, String hash, long version) { return tx(() -> delegate.retry(a,id,key,hash,version)); }
    @Override public List<Publication> publications(UUID owner) { return tx(() -> delegate.publications(owner)); }
    @Override public void published(Publication item, boolean confirmed) { tx(() -> { delegate.published(item,confirmed); return null; }); }
    @Override public Command claim(Publication item, UUID owner) { return tx(() -> {
        Command command=delegate.claim(item,owner);if(command!=null)barrier.await("claim-before-commit");return command;
    }); }
    @Override public Command lookup(UUID id) { return tx(() -> delegate.lookup(id)); }
    @Override public void renew(Command c) { guarded(c,true,() -> { delegate.renew(c); return null; }); }
    @Override public void check(Command c) { tx(() -> { delegate.check(c); return null; }); }
    @Override public void checkOwnership(Command c) { tx(() -> { delegate.checkOwnership(c); return null; }); }
    @Override public void beforeCall(Command c) { guarded(c,true,() -> { delegate.beforeCall(c); return null; }); }
    @Override public Receipt accept(Command c, String hash) {
        return tx(()->{
            Receipt previous=delegate.accept(c,hash);
            if(previous==null)TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void beforeCommit(boolean readOnly){delegate.check(c);}
            });
            return previous;
        });
    }
    @Override public Receipt receipt(Command c) { return tx(() -> delegate.receipt(c)); }
    @Override public void commitReceipt(Command c, Receipt receipt, String json, String hash) { guarded(c,true,() -> { delegate.commitReceipt(c,receipt,json,hash); return null; }); }
    @Override public void complete(Command c, Receipt receipt) { guarded(c,true,() -> { delegate.complete(c,receipt); return null; }); }
    @Override public void uncertain(Command c, String code) { guarded(c,false,() -> { delegate.uncertain(c,code); return null; }); }
    @Override public void quarantine(UUID id, String reason, String hash) { tx(() -> { delegate.quarantine(id,reason,hash); return null; }); }
    @Override public int expireUnresolved(){return tx(delegate::expireUnresolved);}
}
