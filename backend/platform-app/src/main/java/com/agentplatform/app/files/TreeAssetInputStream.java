package com.agentplatform.app.files;

import com.agentplatform.domain.files.*;
import com.agentplatform.domain.files.FileMutationData.TreeFile;
import java.io.*;
import java.util.List;

/** Sequential known-length version streams; holds at most one S3 connection. */
final class TreeAssetInputStream extends InputStream {
    private final List<TreeFile> files;
    private final AssetStore assets;
    private final Runnable guard;
    private int index;
    private AssetStore.Content content;
    private InputStream checked;
    private boolean closed;
    TreeAssetInputStream(List<TreeFile> files,AssetStore assets,Runnable guard) {
        this.files=files.stream().filter(f->"FILE".equals(f.kind())).toList();this.assets=assets;this.guard=guard;
    }
    private boolean next() throws IOException {
        if(closed)throw new IOException("Restore transport closed");
        if(checked!=null)return true;
        if(index>=files.size())return false;
        var file=files.get(index);guard.run();content=assets.open(file.objectKey(),null,null);
        if(content.bytes()!=file.sizeBytes()||content.totalBytes()!=file.sizeBytes()) {content.close();content=null;throw new FileFailure(503,"ASSET_IDENTITY_MISMATCH");}
        checked=new AuthorizedAssetStream(content.stream(),file.sizeBytes(),file.sha256(),guard);return true;
    }
    @Override public int read(byte[] bytes,int offset,int length) throws IOException {
        if(length==0)return 0;
        while(next()) {
            int count=checked.read(bytes,offset,length);
            if(count!=-1)return count;
            content.close();content=null;checked=null;index++;
        }
        return -1;
    }
    @Override public int read() throws IOException {byte[] one=new byte[1];return read(one,0,1)==-1?-1:one[0]&255;}
    @Override public void close() throws IOException {if(closed)return;closed=true;if(content!=null)content.close();}
}
