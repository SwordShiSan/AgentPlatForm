package com.agentplatform.infrastructure.workspace;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Strips fixed helper framing; EOF is withheld until integrity and actual exit are confirmed. */
public final class CapturedFileStream extends InputStream {
    public record Descriptor(UUID operationId,long bytes,String sha256) {}
    private static final JsonMapper JSON=JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final InputStream source;
    private final Descriptor descriptor;
    private final Runnable confirmedExit,authority;
    private final MessageDigest digest;
    private long remaining,nextCheck;
    private boolean verified,closed;
    private IOException failed;
    public static Descriptor descriptor(InputStream source,UUID operation,long maximum) throws IOException {
        if(operation==null||maximum<0||maximum>104857600L)throw new IllegalArgumentException("Invalid capture admission");
        var line=new ByteArrayOutputStream();
        while(true) {
            int value=source.read();if(value==-1)throw new EOFException("FILE_CAPTURE_DESCRIPTOR_MISSING");
            if(value=='\n')break;
            if(line.size()>=8192)throw new IOException("FILE_CAPTURE_DESCRIPTOR_TOO_LARGE");line.write(value);
        }
        try {
            var fields=JSON.readValue(line.toByteArray(),Map.class);
            if(!fields.keySet().equals(Set.of("protocol","operationId","state","bytes","sha256"))
                    ||!"file-capture-v1".equals(fields.get("protocol"))||!"DESCRIBED".equals(fields.get("state"))
                    ||!operation.toString().equals(fields.get("operationId"))
                    ||!(fields.get("bytes") instanceof Integer||fields.get("bytes") instanceof Long)
                    ||!(fields.get("sha256") instanceof String hash)||!hash.matches("[a-f0-9]{64}"))
                throw new IOException("FILE_CAPTURE_DESCRIPTOR_REJECTED");
            long bytes=((Number)fields.get("bytes")).longValue();
            if(bytes<0||bytes>maximum)throw new IOException("FILE_CAPTURE_EXCEEDS_RESERVATION");
            return new Descriptor(operation,bytes,hash);
        }catch(RuntimeException malformed){throw new IOException("FILE_CAPTURE_DESCRIPTOR_REJECTED",malformed);}
    }
    public static byte[] ready(Descriptor descriptor) {
        return (JSON.writeValueAsString(Map.of("protocol","file-capture-v1","operationId",descriptor.operationId(),"ready",true,"sha256",descriptor.sha256()))+"\n")
                .getBytes(StandardCharsets.US_ASCII);
    }
    public CapturedFileStream(InputStream source,Descriptor descriptor,Runnable authority,Runnable confirmedExit) {
        this.source=Objects.requireNonNull(source);this.descriptor=Objects.requireNonNull(descriptor);
        this.authority=Objects.requireNonNull(authority);this.confirmedExit=Objects.requireNonNull(confirmedExit);
        if(descriptor.operationId()==null||descriptor.bytes()<0||descriptor.bytes()>104857600L||descriptor.sha256()==null||!descriptor.sha256().matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Invalid capture descriptor");
        remaining=descriptor.bytes();
        try {digest=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    public boolean verified(){return verified;}
    private void check() throws IOException {
        if(failed!=null)throw failed;if(closed)throw new IOException("FILE_CAPTURE_STREAM_CLOSED");
        if(System.nanoTime()>=nextCheck) {
            try{authority.run();}catch(RuntimeException rejected){throw new IOException("FILE_CAPTURE_AUTHORITY_LOST",rejected);}
            nextCheck=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(250);
        }
    }
    private int finish() throws IOException {
        byte[] trailer=source.readNBytes(32);
        if(trailer.length!=32||!MessageDigest.isEqual(trailer,HexFormat.of().parseHex(descriptor.sha256()))
                ||!MessageDigest.isEqual(trailer,digest.digest())||source.read()!=-1)
            throw new IOException("FILE_CAPTURE_CONTENT_MISMATCH");
        try{authority.run();confirmedExit.run();}
        catch(RuntimeException unproven){throw new IOException("FILE_CAPTURE_EXIT_UNCONFIRMED",unproven);}
        verified=true;return -1;
    }
    @Override public int read() throws IOException {byte[] one=new byte[1];int count=read(one,0,1);return count==-1?-1:one[0]&255;}
    @Override public int read(byte[] bytes,int offset,int length)throws IOException {
        Objects.checkFromIndexSize(offset,length,bytes.length);
        if(length==0)return 0;
        try {
            check();if(verified)return -1;if(remaining==0)return finish();
            int count=source.read(bytes,offset,(int)Math.min(Math.min(length,65536),remaining));
            if(count==-1)throw new EOFException("FILE_CAPTURE_CONTENT_INCOMPLETE");
            if(count>0){digest.update(bytes,offset,count);remaining-=count;}return count;
        }catch(IOException failure){failed=failure;throw failure;}
    }
    @Override public void close()throws IOException {if(!closed){closed=true;source.close();}}
}
