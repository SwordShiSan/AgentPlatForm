package com.agentplatform.app.files;

import com.agentplatform.domain.files.*;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

public final class TransactionalFileTransferPermit implements FileTransferPermit {
    private final FileTransferPermit delegate;
    private final TransactionTemplate transactions;
    public TransactionalFileTransferPermit(FileTransferPermit delegate,TransactionTemplate transactions){this.delegate=delegate;this.transactions=transactions;}
    private <T>T tx(Supplier<T> action){
        try{return transactions.execute(status->action.get());}
        catch(DataAccessException|TransactionException unavailable){throw new FileFailure(503,"FILE_TRANSFER_ADMISSION_UNAVAILABLE");}
    }
    @Override public UUID acquire(String key){return tx(()->delegate.acquire(key));}
    @Override public void release(UUID owner){tx(()->{delegate.release(owner);return null;});}
}
