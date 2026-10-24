package com.agentplatform.app.files;

import com.agentplatform.domain.files.*;
import com.agentplatform.domain.files.FileData.*;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** Application transaction boundary; all external byte transfer happens outside these calls. */
public final class TransactionalFileRepository implements FileRepository {
    private final FileRepository delegate;
    private final TransactionTemplate transactions;
    @Override public List<Upload> uploadReconciliations(UUID after){return tx(()->delegate.uploadReconciliations(after));}
    @Override public Upload fenceUploadReconciliation(Upload observed){return tx(()->delegate.fenceUploadReconciliation(observed));}
    @Override public void guardUploadReconciliation(Upload fenced){tx(()->{delegate.guardUploadReconciliation(fenced);return null;});}
    @Override public void confirmRecoveredUpload(Upload fenced){tx(()->{delegate.confirmRecoveredUpload(fenced);return null;});}
    public TransactionalFileRepository(FileRepository delegate, TransactionTemplate transactions) {
        this.delegate = delegate; this.transactions = transactions;
    }
    private <T> T tx(Supplier<T> action) {
        try { return transactions.execute(status -> action.get()); }
        catch (DuplicateKeyException | ConcurrencyFailureException conflict) { throw new FileFailure(409, "FILE_CONCURRENT_CHANGE"); }
        catch (DataAccessException | TransactionException unavailable) {
            var failure = new FileFailure(503, "FILE_STORAGE_UNAVAILABLE");failure.initCause(unavailable);throw failure;
        }
    }
    @Override public Upload create(Actor a, UUID w, UUID r, String hash, UploadRequest body) { return tx(() -> delegate.create(a,w,r,hash,body)); }
    @Override public Upload upload(Actor a, UUID id) { return tx(() -> delegate.upload(a,id)); }
    @Override public Upload beginTransfer(Actor a, UUID id, UUID owner) { return tx(() -> delegate.beginTransfer(a,id,owner)); }
    @Override public void transferVerified(Actor a, UUID id, UUID owner) { tx(() -> {delegate.transferVerified(a,id,owner);return null;}); }
    @Override public void transferUncertain(Actor a, UUID id, UUID owner) { tx(() -> {delegate.transferUncertain(a,id,owner);return null;}); }
    @Override public void transferRejected(Actor a, UUID id, UUID owner) { tx(() -> {delegate.transferRejected(a,id,owner);return null;}); }
    @Override public void guardTransfer(Actor a, UUID id, UUID owner) { tx(() -> {delegate.guardTransfer(a,id,owner);return null;}); }
    @Override public Operation completeUpload(Actor a, UUID id) { return tx(() -> delegate.completeUpload(a,id)); }
    @Override public List<Entry> list(Actor a, UUID w, String parent, String after, int limit) { return tx(() -> delegate.list(a,w,parent,after,limit)); }
    @Override public Entry file(Actor a, UUID id) { return tx(() -> delegate.file(a,id)); }
    @Override public Version version(Actor a, UUID id, UUID version) { return tx(() -> delegate.version(a,id,version)); }
    @Override public Usage usage(Actor a, UUID w) { return tx(() -> delegate.usage(a,w)); }
    @Override public Operation operation(Actor a, UUID id) { return tx(() -> delegate.operation(a,id)); }
    @Override public List<Publication> publications(UUID owner) { return tx(() -> delegate.publications(owner)); }
    @Override public void published(Publication p, boolean success) { tx(() -> {delegate.published(p,success);return null;}); }
    @Override public Command claim(Publication p, UUID owner) { return tx(() -> delegate.claim(p,owner)); }
    @Override public void renew(Command c) { tx(() -> {delegate.renew(c);return null;}); }
    @Override public void guard(Command c) { tx(() -> {delegate.guard(c);return null;}); }
    @Override public void beforeCall(Command c) { tx(() -> {delegate.beforeCall(c);return null;}); }
    @Override public Receipt receipt(Command c) { return tx(() -> delegate.receipt(c)); }
    @Override public void commitProjection(Command c, Receipt r) { tx(() -> {delegate.commitProjection(c,r);return null;}); }
    @Override public void uncertain(Command c, String code) { tx(() -> {delegate.uncertain(c,code);return null;}); }
    @Override public void blockExpired() { tx(() -> {delegate.blockExpired();return null;}); }
    @Override public void quarantine(UUID id,String code,String hash) { tx(() -> {delegate.quarantine(id,code,hash);return null;}); }
    @Override public List<Command> reconciliations(){return tx(delegate::reconciliations);}
    @Override public void observeProjection(Command c){tx(()->{delegate.observeProjection(c);return null;});}
    @Override public void reconcileProjection(Command c,Receipt r){tx(()->{delegate.reconcileProjection(c,r);return null;});}
    @Override public void scheduleScan(){tx(()->{delegate.scheduleScan();return null;});}
    @Override public Upload claimCleanup(){return tx(delegate::claimCleanup);}
    @Override public void cleanupComplete(Upload u){tx(()->{delegate.cleanupComplete(u);return null;});}
}
