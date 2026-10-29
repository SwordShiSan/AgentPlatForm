package com.agentplatform.app.files;

import com.agentplatform.domain.files.*;
import java.io.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** One persistent transfer slot shared by API, worker and runner. No DB transaction spans network IO. */
public final class SerialAssetStore implements AssetStore {
    private final AssetStore delegate;
    private final FileTransferPermit permits;
    private final boolean requireRunnerReceipt;
    private final java.util.concurrent.atomic.AtomicReference<UUID> stoppedPendingRelease=new java.util.concurrent.atomic.AtomicReference<>();
    public SerialAssetStore(AssetStore delegate,FileTransferPermit permits){this(delegate,permits,false);}
    public SerialAssetStore(AssetStore delegate,FileTransferPermit permits,boolean requireRunnerReceipt){this.delegate=delegate;this.permits=permits;this.requireRunnerReceipt=requireRunnerReceipt;}
    private UUID admit(String key) {
        flushStoppedTransfers();
        return permits.acquire(key);
    }
    @Override public void flushStoppedTransfers() {
        var pending=stoppedPendingRelease.get();
        if(pending!=null){permits.release(pending);stoppedPendingRelease.compareAndSet(pending,null);}
    }
    private void releaseStopped(UUID owner) {
        try{permits.release(owner);}
        catch(RuntimeException unavailable){stoppedPendingRelease.set(owner);throw unavailable;}
    }
    @Override public Identity put(String key,long bytes,String hash,InputStream input) {
        var owner=admit(key);
        try{return delegate.put(key,bytes,hash,input);}finally{releaseStopped(owner);}
    }
    @Override public Identity copy(String source,String target,long bytes,String hash,Runnable authorize) {
        var owner=admit(target);Content content=null;
        try {
            authorize.run();content=delegate.open(source,null,null);
            if(content.bytes()!=bytes||content.totalBytes()!=bytes)throw new FileFailure(503,"ASSET_IDENTITY_MISMATCH");
            return delegate.put(target,bytes,hash,new AuthorizedAssetStream(content.stream(),bytes,hash,authorize));
        } finally {
            // A failed source close retains the original permit; it is not a stopped transfer.
            if(content!=null)try{content.close();}catch(IOException failure){throw new FileFailure(503,"ASSET_CLOSE_UNCONFIRMED");}
            releaseStopped(owner);
        }
    }
    @Override public Identity inspect(String key) {
        var owner=admit(key);try{return delegate.inspect(key);}finally{releaseStopped(owner);}
    }
    @Override public void deleteUncommitted(String key) {
        var owner=admit(key);try{delegate.deleteUncommitted(key);}finally{releaseStopped(owner);}
    }
    @Override public Content open(String key,Long first,Long last) {
        UUID owner=admit(key);final Content content;
        try{content=delegate.open(key,first,last);}catch(RuntimeException failed){releaseStopped(owner);throw failed;}
        var released=new AtomicBoolean();
        var stream=new FilterInputStream(content.stream()) {
            private final java.util.concurrent.atomic.AtomicInteger readers=new java.util.concurrent.atomic.AtomicInteger();
            private final AtomicBoolean closing=new AtomicBoolean();
            private volatile boolean closeCompleted;
            private volatile IOException closeFailure;
            private volatile RuntimeException closeRuntimeFailure;
            private void stopped() {
                if(!requireRunnerReceipt&&closeCompleted&&readers.get()==0&&released.compareAndSet(false,true))releaseStopped(owner);
            }
            @Override public int read()throws IOException{byte[] one=new byte[1];int count=read(one,0,1);return count==-1?-1:one[0]&255;}
            @Override public int read(byte[] bytes,int offset,int length)throws IOException {
                readers.incrementAndGet();
                try{if(closing.get())throw new IOException("Asset stream is closed");return in.read(bytes,offset,length);}
                finally{readers.decrementAndGet();stopped();}
            }
            @Override public long skip(long length)throws IOException {
                readers.incrementAndGet();
                try{if(closing.get())throw new IOException("Asset stream is closed");return in.skip(length);}
                finally{readers.decrementAndGet();stopped();}
            }
            @Override public void close()throws IOException {
                if(closing.compareAndSet(false,true)) {
                    // A failed close cannot serve as evidence that the transport ended.
                    try{super.close();closeCompleted=true;stopped();}
                    catch(IOException failed){closeFailure=failed;throw failed;}
                    catch(RuntimeException failed){closeRuntimeFailure=failed;throw failed;}
                } else if(closeFailure!=null)throw closeFailure;
                else if(closeRuntimeFailure!=null)throw closeRuntimeFailure;
                else if(!closeCompleted)throw new IOException("ASSET_CLOSE_PENDING");
            }
        };
        return new Content(stream,content.bytes(),content.totalBytes());
    }
    @Override public void close(){delegate.close();}
}
