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
    private final Path work;
    private final ScheduledExecutorService timers;
    private final boolean ownsTimers;
    private volatile PreparedRun active;
    private volatile ExecutionControl currentControl;
    private final AtomicBoolean unavailable=new AtomicBoolean();
    private final ThreadPoolExecutor cancellationExecutor=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),
            task->{var thread=new Thread(task,"execution-cancel");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
    public boolean accepting() { return !unavailable.get(); }
    private volatile Lease current;
    public ChatExecution(ChatRepository repository,ChatProvider provider,CredentialCipher cipher,Path work){this(repository,provider,cipher,work,Executors.newScheduledThreadPool(2),true);}
    public ChatExecution(ChatRepository repository,ChatProvider provider,CredentialCipher cipher,Path work,ScheduledExecutorService timers){this(repository,provider,cipher,work,timers,false);}
    private ChatExecution(ChatRepository repository,ChatProvider provider,CredentialCipher cipher,Path work,ScheduledExecutorService timers,boolean ownsTimers){this.repository=repository;this.provider=provider;this.cipher=cipher;this.work=work;this.timers=timers;this.ownsTimers=ownsTimers;}
    /** Compatibility for existing probes; private state always uses the fenced repository. */
    public ChatExecution(ChatRepository repository,ChatProvider provider,CredentialCipher cipher,StateStorage ignored,Path work){this(repository,provider,cipher,work);}
    public void run(Lease lease){
        if(!accepting())throw new ChatFailure(503,"EXECUTION_STOP_UNCONFIRMED");
        current=lease;var control=new ExecutionControl(cancellationExecutor);currentControl=control;
        Instant deadline=Instant.now().plusSeconds(120);AtomicReference<Throwable> asynchronousFailure=new AtomicReference<>();
        AtomicReference<ExecutionLedger.CallResult> accepted=new AtomicReference<>();AtomicReference<Round> acceptedRound=new AtomicReference<>();
        var batch=new Batch(lease);String terminal="FAILED",reason="MODEL_EXECUTION_FAILED";boolean completed=false;
        try{
            List<Message> history=repository.history(lease);
            Scope scope=new Scope(lease.spaceId().toString(),lease.userId().toString(),lease.conversationId().toString(),lease.taskId().toString(),lease.attemptId().toString(),lease.fence(),false);
            AtomicReference<UUID> turnId=new AtomicReference<>();String frozenModelName=repository.modelName(lease);
            ModelGateway metered=new ModelGateway(){
                @Override public String modelName(Scope ignored){return frozenModelName;}
                public CompletionStage<Round> complete(Scope s,List<Message> messages,List<com.agentplatform.types.harness.HarnessData.Call> tools){return stream(s,messages,tools,text->{});}
                public CompletionStage<Round> stream(Scope s,List<Message> messages,List<com.agentplatform.types.harness.HarnessData.Call> tools,Consumer<String> onText){
                    if(!tools.isEmpty())return CompletableFuture.failedFuture(new ChatFailure(422,"MODEL_CAPABILITY_MISMATCH"));
                    var result=new CompletableFuture<Round>();AtomicReference<CompletableFuture<?>> pending=new AtomicReference<>();
                    result.whenComplete((v,e)->{if(result.isCancelled()&&pending.get()!=null)pending.get().cancel(true);});
                    physical(lease,messages,text->{if(control.closing())throw new ChatFailure(409,"TASK_CANCEL_REQUESTED");batch.append(text);onText.accept(text);},result,pending,deadline,0,false,accepted,acceptedRound,control);
                    return result;
                }
            };
            RuntimeStatePort manifests=new RuntimeStatePort(){public void verifyRestore(Scope s,Bundle b){throw new ChatFailure(409,"CHECKPOINT_RECOVERY_NOT_AVAILABLE");}public void seal(Scope s,Bundle b){FixtureFaults.pause("checkpoint-before-seal");repository.sealCheckpoint(lease,b);FixtureFaults.pause("checkpoint-after-seal");}public List<Message> checkpointHistory(Scope s){return repository.checkpointHistory(lease);}};
            StateStorage attemptState=new StateStorage(){
                private void scope(Scope s){if(!scope.equals(s))throw new ChatFailure(403,"STATE_SCOPE_MISMATCH");}
                public StateSlot load(Scope s,String key){scope(s);return repository.state(lease,key);}
                public long compareAndSwap(Scope s,String key,long version,byte[] body){scope(s);return repository.stateCas(lease,key,version,body);}
                public Set<String> keys(Scope s){scope(s);return repository.stateKeys(lease);}
                public void delete(Scope s,String key){scope(s);repository.stateDelete(lease,key);}
            };
            var factory=new AgentScopeRuntimeFactory(metered,(s,c)->Decision.DENY,(s,c)->CompletableFuture.failedFuture(new ChatFailure(422,"TOOLS_NOT_AVAILABLE")),attemptState,manifests,List.of(),work);
            LoopBoundary boundary=new LoopBoundary(){
                public CompletionStage<List<Message>> beforeModel(Scope s,List<Message> ignored){turnId.set(repository.beginTurn(lease));return CompletableFuture.completedFuture(history);}
                public CompletionStage<Void> afterModelCommitted(Scope s,Turn t){var call=accepted.get();if(call==null)throw new ChatFailure(409,"ACCEPTED_CALL_MISSING");repository.commitRound(lease,turnId.get(),t,call);FixtureFaults.pause("round-before-checkpoint");return CompletableFuture.completedFuture(null);}
                public CompletionStage<Void> beforeTool(Scope s,com.agentplatform.types.harness.HarnessData.Call c){return CompletableFuture.failedFuture(new ChatFailure(422,"TOOLS_NOT_AVAILABLE"));}
                public CompletionStage<Void> afterToolCommitted(Scope s,com.agentplatform.types.harness.HarnessData.Call c,Observation o){return CompletableFuture.failedFuture(new ChatFailure(422,"TOOLS_NOT_AVAILABLE"));}
                public CompletionStage<Completion> beforeStop(Scope s,Message message){return CompletableFuture.completedFuture(message.text().isBlank()?Completion.INCOMPLETE:Completion.PASS);}
            };
            PreparedRun prepared=factory.prepare(new Request(scope,ChatRuntime.bundle(),List.of(history.getLast()),false,List.of(),1),boundary);active=prepared;control.run(prepared);
            control.timer(timers.scheduleAtFixedRate(()->{
                try(var ticket=control.enter()){batch.flush();repository.check(lease);}
                catch(Throwable error){if(!control.closing()){asynchronousFailure.compareAndSet(null,error);control.cancel();}}
            },100,100,TimeUnit.MILLISECONDS));
            control.timer(timers.scheduleAtFixedRate(()->{
                try(var ticket=control.enter()){repository.heartbeat(lease);}
                catch(Throwable error){if(!control.closing()){asynchronousFailure.compareAndSet(null,error);control.cancel();}}
            },10,10,TimeUnit.SECONDS));
            Object outcome=CompletableFuture.anyOf(prepared.start().toCompletableFuture(),control.cancellation().toCompletableFuture()).get(
                    Math.max(1,java.time.Duration.between(Instant.now(),deadline).toMillis()),TimeUnit.MILLISECONDS);
            Throwable failure=asynchronousFailure.get();if(failure!=null)throw new CompletionException(failure);
            if(outcome==null||control.closing())throw new ChatFailure(409,"TASK_CANCEL_REQUESTED");
            batch.flush();if(((Outcome)outcome).completion()!=Completion.PASS)throw new ChatFailure(503,"MODEL_INCOMPLETE");
            terminal="SUCCEEDED";reason=null;completed=true;
        }catch(Throwable failure){
            Throwable error=failure;
            for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof ChatFailure||cause instanceof TimeoutException){error=cause;break;}
            if(asynchronousFailure.get()!=null)error=asynchronousFailure.get();
            reason=error instanceof ChatFailure f?f.code():error instanceof TimeoutException?"PROVIDER_TIMEOUT":"MODEL_EXECUTION_FAILED";
            terminal="TASK_CANCEL_REQUESTED".equals(reason)?"CANCELLED":"CONTEXT_REQUIRED_TOO_LARGE".equals(reason)?"BLOCKED":"FAILED";
        }finally{
            boolean quiet=control.quiesce(!completed);
            try{
                if(quiet){
                    var value=accepted.get();var round=acceptedRound.get();
                    CancellationLedger.KnownUsage usage=value!=null&&value.usageKnown()&&round!=null?new CancellationLedger.KnownUsage(value.call(),round,value.requestId(),value.real()):null;
                    repository.stopped(lease,terminal,reason,usage,control.receipt(lease.taskId()+":"+lease.attemptId()+":"+lease.ownerId()+":"+lease.fence()));
                }else{unavailable.set(true);repository.stopUnconfirmed(lease);}
            }catch(Throwable lost){unavailable.set(true);System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,"Attempt requires stopped-owner reconciliation; terminationConfirmed="+quiet);}
            active=null;current=null;currentControl=null;
        }
    }
    private void physical(Lease l,List<Message> messages,Consumer<String> text,CompletableFuture<Round> result,AtomicReference<CompletableFuture<?>> pending,Instant deadline,int retries,boolean repaired,AtomicReference<ExecutionLedger.CallResult> accepted,AtomicReference<Round> acceptedRound,ExecutionControl control){
        if(result.isDone())return;
        try(var ticket=control.enter()){
            if(Instant.now().isAfter(deadline)){result.completeExceptionally(new ChatFailure(504,"PROVIDER_TIMEOUT"));return;}
            ChatData.Call call=repository.beginCall(l,provider.real());String key;
            try{key=cipher.decrypt(repository.credential(l,call.id()));repository.startCall(l,call,provider.real());}
            catch(Throwable error){try{repository.settle(l,call,null,null,"CALL_NOT_SENT",false);}catch(Exception ignored){}result.completeExceptionally(error);return;}
            AtomicBoolean emitted=new AtomicBoolean();CompletableFuture<ChatProvider.Result> future;
            try{var exchange=provider.open(repository.modelName(l),key,messages,value->{emitted.set(true);text.accept(value);});control.exchange(exchange);future=exchange.result().toCompletableFuture();}
            catch(Throwable error){future=CompletableFuture.failedFuture(error);}
            pending.set(future);if(result.isCancelled())future.cancel(true);
            var callback=control.enter();
            future.whenComplete((value,error)->{
                try{
                    if(control.closing()){
                        if(value!=null&&value.usageKnown()){acceptedRound.set(value.round());accepted.set(new ExecutionLedger.CallResult(call,true,value.requestId(),provider.real()));}
                        result.completeExceptionally(new ChatFailure(409,"TASK_CANCEL_REQUESTED"));return;
                    }
                    if(error!=null){
                        Throwable cause=error;while(cause instanceof CompletionException&&cause.getCause()!=null)cause=cause.getCause();
                        String code=cause instanceof ChatFailure f?f.code():"PROVIDER_REQUEST_FAILED";
                        repository.settle(l,call,null,null,code,provider.real());
                        if(!emitted.get()&&retries<2&&Set.of("PROVIDER_RATE_LIMITED","PROVIDER_SERVER_REJECTED").contains(code)&&!result.isDone()){
                            control.timer(timers.schedule(()->physical(l,messages,text,result,pending,deadline,retries+1,repaired,accepted,acceptedRound,control),retries==0?1:3,TimeUnit.SECONDS));return;
                        }result.completeExceptionally(cause);return;
                    }
                    boolean incompleteTransport="unknown".equals(value.round().finishReason());
                    boolean empty=value.round().text().isBlank()&&value.round().calls().isEmpty()&&!emitted.get();
                    if(!"stop".equals(value.round().finishReason())||empty){
                        repository.settle(l,call,value.usageKnown()?value.round():null,value.requestId(),value.usageKnown()?null:incompleteTransport?"PROVIDER_REQUEST_FAILED":"PROVIDER_USAGE_MISSING",provider.real());
                        if(!"stop".equals(value.round().finishReason())){result.completeExceptionally(new ChatFailure(503,"PROVIDER_TRUNCATED"));return;}
                        if(!repaired){physical(l,messages,text,result,pending,deadline,retries,true,accepted,acceptedRound,control);return;}
                        result.completeExceptionally(new ChatFailure(503,"PROVIDER_EMPTY_RESPONSE"));return;
                    }
                    acceptedRound.set(value.round());accepted.set(new ExecutionLedger.CallResult(call,value.usageKnown(),value.requestId(),provider.real()));
                    FixtureFaults.pause("response-before-round");result.complete(value.round());
                }catch(Throwable failure){result.completeExceptionally(failure);}
                finally{try{callback.close();}catch(Exception impossible){throw new IllegalStateException(impossible);}}
            });
        }catch(Throwable failure){result.completeExceptionally(failure);}
    }
    private final class Batch {
        private final Lease lease;private final StringBuilder buffer=new StringBuilder();private int bytes;
        Batch(Lease lease){this.lease=lease;}
        synchronized void append(String text){buffer.append(text);bytes+=text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;if(bytes>=4096)flush();}
        synchronized void flush(){if(buffer.isEmpty())return;repository.delta(lease,buffer.toString());buffer.setLength(0);bytes=0;}
    }
    @Override public void close(){if(currentControl!=null)currentControl.cancel();cancellationExecutor.shutdown();if(ownsTimers)timers.shutdownNow();}
}
