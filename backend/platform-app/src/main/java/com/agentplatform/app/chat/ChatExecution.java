package com.agentplatform.app.chat;

import com.agentplatform.domain.chat.*;
import com.agentplatform.domain.chat.ChatData.Lease;
import com.agentplatform.domain.chat.ChatData;
import com.agentplatform.domain.harness.HarnessPorts.*;
import com.agentplatform.types.harness.HarnessData.*;
import com.agentplatform.infrastructure.chat.CredentialCipher;
import com.agentplatform.infrastructure.harness.agentscope.AgentScopeRuntimeFactory;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/** One AMQP consumer callback owns one attempt; browser lifetimes never own model subscriptions. */
public final class ChatExecution implements AutoCloseable {
    private final ChatRepository repository;
    private final ChatProvider provider;
    private final CredentialCipher cipher;
    private final StateStorage state;
    private final Path work;
    private final ScheduledExecutorService timers=Executors.newScheduledThreadPool(2);
    private volatile PreparedRun active;
    private volatile Lease current;
    public ChatExecution(ChatRepository repository,ChatProvider provider,CredentialCipher cipher,StateStorage state,Path work){this.repository=repository;this.provider=provider;this.cipher=cipher;this.state=state;this.work=work;}
    public void run(Lease lease){
        current=lease;Instant deadline=Instant.now().plusSeconds(120);AtomicReference<Throwable> asynchronousFailure=new AtomicReference<>();
        var batch=new Batch(lease);ScheduledFuture<?> flushing=null,heartbeat=null;
        try{
            List<Message> history=repository.history(lease);
            Scope scope=new Scope(lease.spaceId().toString(),lease.userId().toString(),lease.conversationId().toString(),lease.taskId().toString(),lease.attemptId().toString(),lease.fence(),false);
            ModelGateway metered=new ModelGateway(){
                public CompletionStage<Round> complete(Scope s,List<Message> messages,List<com.agentplatform.types.harness.HarnessData.Call> tools){return stream(s,messages,tools,text->{});}
                public CompletionStage<Round> stream(Scope s,List<Message> messages,List<com.agentplatform.types.harness.HarnessData.Call> tools,Consumer<String> onText){
                    if(!tools.isEmpty())return CompletableFuture.failedFuture(new ChatFailure(422,"MODEL_CAPABILITY_MISMATCH"));
                    var result=new CompletableFuture<Round>();AtomicReference<CompletableFuture<?>> pending=new AtomicReference<>();
                    result.whenComplete((v,e)->{if(result.isCancelled()&&pending.get()!=null)pending.get().cancel(true);});
                    physical(lease,messages,text->{batch.append(text);onText.accept(text);},result,pending,deadline,0,false);
                    return result;
                }
            };
            RuntimeStatePort manifests=new RuntimeStatePort(){public void verifyRestore(Scope s,Bundle b){throw new ChatFailure(409,"CHECKPOINT_RECOVERY_NOT_AVAILABLE");}public void seal(Scope s,Bundle b){repository.check(lease);}};
            String prefix="attempt/"+lease.attemptId()+"/";
            StateStorage attemptState=new StateStorage(){
                public StateSlot load(Scope s,String key){return state.load(s,prefix+key);}
                public long compareAndSwap(Scope s,String key,long version,byte[] body){return state.compareAndSwap(s,prefix+key,version,body);}
                public Set<String> keys(Scope s){return state.keys(s).stream().filter(k->k.startsWith(prefix)).map(k->k.substring(prefix.length())).collect(java.util.stream.Collectors.toUnmodifiableSet());}
                public void delete(Scope s,String key){state.delete(s,prefix+key);}
            };
            var factory=new AgentScopeRuntimeFactory(metered,(s,c)->Decision.DENY,(s,c)->CompletableFuture.failedFuture(new ChatFailure(422,"TOOLS_NOT_AVAILABLE")),attemptState,manifests,List.of(),work);
            LoopBoundary boundary=new LoopBoundary(){
                public CompletionStage<List<Message>> beforeModel(Scope s,List<Message> ignored){repository.check(lease);return CompletableFuture.completedFuture(history);}
                public CompletionStage<Void> afterModelCommitted(Scope s,Turn t){repository.check(lease);return CompletableFuture.completedFuture(null);}
                public CompletionStage<Void> beforeTool(Scope s,com.agentplatform.types.harness.HarnessData.Call c){return CompletableFuture.failedFuture(new ChatFailure(422,"TOOLS_NOT_AVAILABLE"));}
                public CompletionStage<Void> afterToolCommitted(Scope s,com.agentplatform.types.harness.HarnessData.Call c,Observation o){return CompletableFuture.failedFuture(new ChatFailure(422,"TOOLS_NOT_AVAILABLE"));}
                public CompletionStage<Completion> beforeStop(Scope s,Message message){return CompletableFuture.completedFuture(message.text().isBlank()?Completion.INCOMPLETE:Completion.PASS);}
            };
            Bundle bundle=ChatRuntime.bundle();
            // Context lives in the platform ledger; Request's current-user input remains the only input authority.
            active=factory.prepare(new Request(scope,bundle,List.of(history.getLast()),false,List.of(),1),boundary);
            flushing=timers.scheduleAtFixedRate(()->{try{batch.flush();repository.check(lease);}catch(Throwable error){asynchronousFailure.compareAndSet(null,error);var run=active;if(run!=null)run.cancel();}},100,100,TimeUnit.MILLISECONDS);
            heartbeat=timers.scheduleAtFixedRate(()->{try{repository.heartbeat(lease);}catch(Throwable error){asynchronousFailure.compareAndSet(null,error);var run=active;if(run!=null)run.cancel();}},10,10,TimeUnit.SECONDS);
            Outcome outcome=active.start().toCompletableFuture().get(
                    Math.max(1,java.time.Duration.between(Instant.now(),deadline).toMillis()),TimeUnit.MILLISECONDS);
            if(flushing!=null)flushing.cancel(false);batch.flush();
            Throwable failure=asynchronousFailure.get();if(failure!=null)throw new CompletionException(failure);
            if(outcome.completion()!=Completion.PASS)throw new ChatFailure(503,"MODEL_INCOMPLETE");
            repository.finish(lease,"SUCCEEDED",null);
        }catch(Throwable failure){
            if(flushing!=null)flushing.cancel(false);if(active!=null)active.cancel();
            Throwable error=failure;
            // Harness/SDK boundaries may add IllegalStateException wrappers. Only typed
            // platform failures cross the public boundary; never expose wrapper messages.
            for(Throwable cause=failure;cause!=null;cause=cause.getCause()){
                if(cause instanceof ChatFailure||cause instanceof TimeoutException){error=cause;break;}
            }
            if(asynchronousFailure.get()!=null)error=asynchronousFailure.get();
            String code=error instanceof ChatFailure f?f.code():error instanceof TimeoutException?"PROVIDER_TIMEOUT":"MODEL_EXECUTION_FAILED";
            try{batch.flush();}catch(Exception ignored){}
            try{repository.finish(lease,"TASK_CANCEL_REQUESTED".equals(code)?"CANCELLED":"CONTEXT_REQUIRED_TOO_LARGE".equals(code)?"BLOCKED":"FAILED",code);}catch(ChatFailure lost){System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,"Attempt requires stopped-owner reconciliation: "+lost.code());}
        }finally{if(flushing!=null)flushing.cancel(false);if(heartbeat!=null)heartbeat.cancel(false);if(active!=null)active.close();active=null;current=null;}
    }
    private void physical(Lease l,List<Message> messages,Consumer<String> text,CompletableFuture<Round> result,AtomicReference<CompletableFuture<?>> pending,Instant deadline,int retries,boolean repaired){
        if(result.isDone())return;if(Instant.now().isAfter(deadline)){result.completeExceptionally(new ChatFailure(504,"PROVIDER_TIMEOUT"));return;}
        ChatData.Call call;String key;
        try{call=repository.beginCall(l,provider.real());}catch(Throwable error){result.completeExceptionally(error);return;}
        try{key=cipher.decrypt(repository.credential(l,call.id()));repository.startCall(l,call,provider.real());}
        catch(Throwable error){try{repository.settle(l,call,null,null,"CALL_NOT_SENT",false);}catch(Exception ignored){}result.completeExceptionally(error);return;}
        AtomicBoolean emitted=new AtomicBoolean();
        CompletableFuture<ChatProvider.Result> future;
        try{future=provider.stream(key,messages,value->{emitted.set(true);text.accept(value);}).toCompletableFuture();}
        catch(Throwable error){future=CompletableFuture.failedFuture(error);}
        pending.set(future);if(result.isCancelled())future.cancel(true);
        future.whenComplete((value,error)->{
            try{
                if(error!=null){
                    Throwable cause=error;while(cause instanceof CompletionException&&cause.getCause()!=null)cause=cause.getCause();
                    String code=cause instanceof ChatFailure f?f.code():"PROVIDER_REQUEST_FAILED";
                    repository.settle(l,call,null,null,code,provider.real());
                    if(!emitted.get()&&retries<2&&Set.of("PROVIDER_RATE_LIMITED","PROVIDER_SERVER_REJECTED").contains(code)&&!result.isDone()){
                        timers.schedule(()->physical(l,messages,text,result,pending,deadline,retries+1,repaired),retries==0?1:3,TimeUnit.SECONDS);return;
                    }result.completeExceptionally(cause);return;
                }
                boolean incompleteTransport="unknown".equals(value.round().finishReason());
                repository.settle(l,call,value.usageKnown()?value.round():null,value.requestId(),value.usageKnown()?null:incompleteTransport?"PROVIDER_REQUEST_FAILED":"PROVIDER_USAGE_MISSING",provider.real());
                if(!"stop".equals(value.round().finishReason())){result.completeExceptionally(new ChatFailure(503,"PROVIDER_TRUNCATED"));return;}
                if(value.round().text().isBlank()&&value.round().calls().isEmpty()&&!emitted.get()&&!repaired){physical(l,messages,text,result,pending,deadline,retries,true);return;}
                if(value.round().text().isBlank()&&!emitted.get()){result.completeExceptionally(new ChatFailure(503,"PROVIDER_EMPTY_RESPONSE"));return;}
                result.complete(value.round());
            }catch(Throwable failure){result.completeExceptionally(failure);}
        });
    }
    private final class Batch {
        private final Lease lease;private final StringBuilder buffer=new StringBuilder();private int bytes;
        Batch(Lease lease){this.lease=lease;}
        synchronized void append(String text){buffer.append(text);bytes+=text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;if(bytes>=4096)flush();}
        synchronized void flush(){if(buffer.isEmpty())return;repository.delta(lease,buffer.toString());buffer.setLength(0);bytes=0;}
    }
    @Override public void close(){if(active!=null)active.cancel();timers.shutdownNow();}
}
