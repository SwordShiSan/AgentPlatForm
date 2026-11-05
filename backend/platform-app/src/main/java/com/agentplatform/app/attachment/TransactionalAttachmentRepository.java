package com.agentplatform.app.attachment;

import com.agentplatform.domain.attachment.*;
import com.agentplatform.domain.attachment.AttachmentData.*;
import com.agentplatform.domain.files.FileData.Actor;
import com.agentplatform.domain.files.FileFailure;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.*;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

public final class TransactionalAttachmentRepository implements AttachmentRepository {
    private final AttachmentRepository delegate;private final TransactionTemplate transactions;
    public TransactionalAttachmentRepository(AttachmentRepository delegate,TransactionTemplate transactions){this.delegate=delegate;this.transactions=transactions;}
    private <T>T tx(Supplier<T> action){try{return transactions.execute(status->action.get());}
        catch(DuplicateKeyException|ConcurrencyFailureException conflict){throw new FileFailure(409,"ATTACHMENT_CONCURRENT_CHANGE");}
        catch(DataAccessException|TransactionException unavailable){var failure=new FileFailure(503,"ATTACHMENT_STORAGE_UNAVAILABLE");failure.initCause(unavailable);throw failure;}}
    private void run(Runnable action){tx(()->{action.run();return null;});}
    @Override public Upload create(Actor a,UUID r,String hash,Create c){return tx(()->delegate.create(a,r,hash,c));}
    @Override public Upload upload(Actor a,UUID id){return tx(()->delegate.upload(a,id));}
    @Override public Upload request(Actor a,UUID id){return tx(()->delegate.request(a,id));}
    @Override public Upload begin(Actor a,UUID id,UUID owner){return tx(()->delegate.begin(a,id,owner));}
    @Override public void guard(Actor a,UUID id,UUID owner){run(()->delegate.guard(a,id,owner));}
    @Override public void verified(Actor a,UUID id,UUID owner){run(()->delegate.verified(a,id,owner));}
    @Override public void uncertain(Actor a,UUID id,UUID owner){run(()->delegate.uncertain(a,id,owner));}
    @Override public void rejected(Actor a,UUID id,UUID owner){run(()->delegate.rejected(a,id,owner));}
    @Override public Upload recoverVerified(Actor a,UUID id,long revision){return tx(()->delegate.recoverVerified(a,id,revision));}
    @Override public Attachment complete(Actor a,UUID id){return tx(()->delegate.complete(a,id));}
    @Override public Attachment get(Actor a,UUID id){return tx(()->delegate.get(a,id));}
    @Override public Usage usage(Actor a){return tx(()->delegate.usage(a));}
}
