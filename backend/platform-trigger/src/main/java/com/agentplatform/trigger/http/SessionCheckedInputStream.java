package com.agentplatform.trigger.http;

import java.io.*;

/** Long HTTP transfers use the existing opaque-session authority, just as persistent SSE does. */
final class SessionCheckedInputStream extends FilterInputStream {
    private final Runnable authorize;
    private long bytes,checkedAtBytes,checkedAtNanos;
    SessionCheckedInputStream(InputStream source,Runnable authorize){super(source);this.authorize=authorize;}
    private void check(){
        long now=System.nanoTime();
        if(checkedAtNanos==0||bytes-checkedAtBytes>=1024*1024||now-checkedAtNanos>=1_000_000_000L){authorize.run();checkedAtBytes=bytes;checkedAtNanos=now;}
    }
    @Override public int read()throws IOException{byte[] value=new byte[1];int count=read(value,0,1);return count==-1?-1:value[0]&255;}
    @Override public int read(byte[] buffer,int offset,int length)throws IOException {
        check();int count=in.read(buffer,offset,length);if(count==-1)authorize.run();else bytes+=count;return count;
    }
}
