package com.agentplatform.app.files;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Byte-count and full-object integrity gate with periodic live authorization. No content buffering. */
final class AuthorizedAssetStream extends FilterInputStream {
    private final Runnable authorize;
    private final long expected;
    private final String expectedHash;
    private final MessageDigest digest;
    private long bytes, checkedAtBytes, checkedAtNanos;
    private boolean ended;
    AuthorizedAssetStream(InputStream source,long expected,String expectedHash,Runnable authorize) {
        super(source);this.expected=expected;this.expectedHash=expectedHash;this.authorize=authorize;
        try {digest=expectedHash==null?null:MessageDigest.getInstance("SHA-256");}
        catch(NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
    private void check() {
        long now=System.nanoTime();
        if(checkedAtNanos==0||bytes-checkedAtBytes>=1024*1024||now-checkedAtNanos>=1_000_000_000L) {
            authorize.run();checkedAtBytes=bytes;checkedAtNanos=now;
        }
    }
    private void end() throws IOException {
        if(ended)return;
        ended=true;
        if(bytes!=expected||(digest!=null&&!HexFormat.of().formatHex(digest.digest()).equals(expectedHash)))
            throw new IOException("FILE_CONTENT_INTEGRITY_FAILURE");
        authorize.run();
    }
    @Override public int read() throws IOException {
        byte[] single=new byte[1];int count=read(single,0,1);return count==-1?-1:single[0]&255;
    }
    @Override public int read(byte[] buffer,int offset,int length) throws IOException {
        check();int count=in.read(buffer,offset,length);
        if(count==-1){end();return -1;}
        bytes+=count;
        if(bytes>expected)throw new IOException("FILE_CONTENT_INTEGRITY_FAILURE");
        if(digest!=null)digest.update(buffer,offset,count);
        return count;
    }
}
