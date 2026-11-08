package com.agentplatform.app.knowledge;

import com.agentplatform.domain.files.AssetStore;
import com.agentplatform.domain.files.FileFailure;
import com.agentplatform.domain.knowledge.KnowledgeEncoder;
import com.agentplatform.domain.knowledge.KnowledgeData.BuildCommand;
import com.agentplatform.domain.knowledge.KnowledgeData.Chunk;
import com.agentplatform.domain.knowledge.KnowledgeRepository;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ScheduledFuture;
import java.time.Duration;
import org.springframework.scheduling.TaskScheduler;

/** Builds an immutable index outside database transactions, persisting each fenced ordinal. */
public final class KnowledgeIndexExecution {
    private static final int MAX_TEXT_BYTES=2*1024*1024,MAX_CHUNKS=10_000,MAX_CHUNK_TOKENS=384;
    private final KnowledgeRepository repository; private final AssetStore assets; private final KnowledgeEncoder encoder;
    private final TaskScheduler timers;
    private final AtomicBoolean running=new AtomicBoolean();
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(KnowledgeIndexExecution.class);
    public KnowledgeIndexExecution(KnowledgeRepository repository,AssetStore assets,KnowledgeEncoder encoder,TaskScheduler timers){this.repository=repository;this.assets=assets;this.encoder=encoder;this.timers=timers;}
    public void run(BuildCommand command){
        if(!running.compareAndSet(false,true))throw new FileFailure(423,"KNOWLEDGE_EXECUTOR_BUSY");
        var renewalFailure=new AtomicReference<RuntimeException>();
        ScheduledFuture<?> heartbeat=timers.scheduleAtFixedRate(()->{
            try{repository.renew(command);}catch(RuntimeException unavailable){renewalFailure.compareAndSet(null,unavailable);}
        },Duration.ofSeconds(10));
        try {
            if(!encoder.modelSha256().equals(command.modelSha256())||!encoder.tokenizerSha256().equals(command.tokenizerSha256()))throw new FileFailure(409,"KNOWLEDGE_MODEL_BUNDLE_MISMATCH");
            checkLease(command,renewalFailure);byte[] bytes=read(command);checkLease(command,renewalFailure);String text=decode(bytes);
            List<Span> spans=split(text);if(spans.size()>MAX_CHUNKS)throw new FileFailure(413,"KNOWLEDGE_CHUNK_LIMIT");
            var hashes=new ArrayList<String>(spans.size());int ordinal=0;
            for(Span span:spans){checkLease(command,renewalFailure);String body=span.body();String chunkHash=sha(body.getBytes(StandardCharsets.UTF_8));
                float[] vector=encoder.embed(body,false);Chunk chunk=new Chunk(ordinal,body,chunkHash,
                        "{\"kind\":\"TEXT_OFFSET\",\"start\":"+span.start()+",\"end\":"+span.end()+"}",span.start(),span.end(),vectorLiteral(vector));
                checkLease(command,renewalFailure);repository.insertChunk(command,chunk);hashes.add(ordinal+":"+chunkHash+":"+span.start()+":"+span.end());ordinal++;
            }
            checkLease(command,renewalFailure);
            repository.complete(command,ordinal,sha(String.join("\n",hashes).getBytes(StandardCharsets.UTF_8)));
        } catch(Throwable failure){
            Throwable root=org.springframework.core.NestedExceptionUtils.getMostSpecificCause(failure);
            StackTraceElement[] frames=root.getStackTrace();
            LOG.warn("Knowledge index failed: code={} type={} root={} at={}",stable(failure),failure.getClass().getSimpleName(),
                    root.getClass().getSimpleName(),frames.length==0?"unknown":frames[0]);
            try{repository.fail(command,stable(failure));}catch(RuntimeException unresolved){failure.addSuppressed(unresolved);throw new IllegalStateException("Knowledge build outcome remains unconfirmed",failure);}
            if(failure instanceof Error error)throw error;
        } finally {heartbeat.cancel(false);running.set(false);}
    }
    private void checkLease(BuildCommand command,AtomicReference<RuntimeException> renewalFailure){
        RuntimeException failure=renewalFailure.get();if(failure!=null)throw new FileFailure(503,"KNOWLEDGE_LEASE_RENEWAL_FAILED");repository.guard(command);
    }
    private byte[] read(BuildCommand command){
        if(command.extractedBytes()<0||command.extractedBytes()>MAX_TEXT_BYTES)throw new FileFailure(413,"KNOWLEDGE_TEXT_LIMIT");
        try(var content=assets.open(command.extractedObjectKey(),null,null);var out=new ByteArrayOutputStream((int)command.extractedBytes())){
            if(content.bytes()!=command.extractedBytes()||content.totalBytes()!=command.extractedBytes())throw new FileFailure(409,"KNOWLEDGE_TEXT_IDENTITY_MISMATCH");
            byte[] block=new byte[32*1024];int count;while((count=content.stream().read(block))!=-1){out.write(block,0,count);if(out.size()>MAX_TEXT_BYTES)throw new FileFailure(413,"KNOWLEDGE_TEXT_LIMIT");}
            byte[] value=out.toByteArray();if(value.length!=command.extractedBytes()||!sha(value).equals(command.extractedSha256()))throw new FileFailure(409,"KNOWLEDGE_TEXT_IDENTITY_MISMATCH");return value;
        }catch(java.io.IOException error){throw new FileFailure(503,"KNOWLEDGE_SOURCE_READ_FAILED");}
    }
    private static String decode(byte[] bytes){try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(Exception invalid){throw new FileFailure(422,"KNOWLEDGE_TEXT_INVALID_UTF8");}}
    private List<Span> split(String text){
        int count=text.codePointCount(0,text.length());List<Span> result=new ArrayList<>();int start=0;
        while(start<count){int low=start+1,high=Math.min(count,start+6000),best=start;
            while(low<=high){int mid=(low+high)>>>1;String candidate=text.substring(text.offsetByCodePoints(0,start),text.offsetByCodePoints(0,mid));
                if(encoder.tokenCount("passage: "+candidate)<=MAX_CHUNK_TOKENS){best=mid;low=mid+1;}else high=mid-1;}
            if(best==start)throw new FileFailure(422,"KNOWLEDGE_SINGLE_TOKEN_OVERFLOW");
            int end=best;while(end>start+1&&!Character.isWhitespace(text.codePointAt(text.offsetByCodePoints(0,end-1)))){int cp=text.codePointBefore(text.offsetByCodePoints(0,end));if("。！？.!?\n".indexOf(cp)>=0)break;end--;}
            if(end<=start)end=best;int from=text.offsetByCodePoints(0,start),to=text.offsetByCodePoints(0,end);String body=text.substring(from,to).strip();
            if(!body.isEmpty())result.add(new Span(start,end,body));start=end;
        }
        return result;
    }
    private static String vectorLiteral(float[] vector){if(vector==null||vector.length!=384)throw new FileFailure(503,"KNOWLEDGE_VECTOR_INVALID");var out=new StringBuilder("[");for(int i=0;i<vector.length;i++){if(!Float.isFinite(vector[i]))throw new FileFailure(503,"KNOWLEDGE_VECTOR_INVALID");if(i>0)out.append(',');out.append(vector[i]);}return out.append(']').toString();}
    private static String stable(Throwable failure){if(failure instanceof FileFailure f&&f.getMessage()!=null&&f.getMessage().matches("[A-Z0-9_]{1,64}"))return f.getMessage();return "KNOWLEDGE_INDEX_FAILED";}
    private static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private record Span(long start,long end,String body){}
}
