package com.agentplatform.app.chat;

import com.agentplatform.domain.chat.*;
import com.agentplatform.domain.chat.ChatData.Lease;
import com.agentplatform.domain.chat.ChatData;
import com.agentplatform.domain.harness.HarnessPorts.*;
import com.agentplatform.types.harness.HarnessData.*;
import com.agentplatform.infrastructure.chat.CredentialCipher;
import com.agentplatform.infrastructure.chat.RecoveryFixtureTool;
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
    private final ToolExecutionPort fixtureTools;
    private volatile PreparedRun active;
    private volatile ExecutionControl currentControl;
    private final AtomicBoolean unavailable=new AtomicBoolean();
    private final ThreadPoolExecutor cancellationExecutor=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),
            task->{var thread=new Thread(task,"execution-cancel");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
    public boolean accepting() { return !unavailable.get(); }
    private volatile Lease current;
    private UUID acceptanceRun;
    private boolean executionStarted;
    private boolean toolApprovalsEnabled;
    private WorkspaceToolRuntime workspaceTools;
    public synchronized void configureWorkspaceTools(WorkspaceToolRuntime runtime) {
        if(executionStarted||fixtureTools!=null||workspaceTools!=null)throw new IllegalStateException("WORKSPACE_TOOL_COMPOSITION_CONFLICT");
        workspaceTools=Objects.requireNonNull(runtime);
    }
    /** Deterministic acceptance only; production has no business tool catalog in R4.3. */
    public synchronized void configureToolApprovals(){
        if(executionStarted||fixtureTools==null||provider.real())throw new IllegalStateException("APPROVAL_FIXTURE_REQUIRED");
        toolApprovalsEnabled=true;
    }
    /** Only the protected acceptance profile calls this; ordinary users have no request-count ceiling. */
    public synchronized void configureAcceptanceWindow(UUID run){
        if(executionStarted||acceptanceRun!=null||!provider.real())throw new IllegalStateException("ACCEPTANCE_WINDOW_NOT_CONFIGURABLE");
        acceptanceRun=Objects.requireNonNull(run);
    }
    public ChatExecution(ChatRepository repository,ChatProvider provider,CredentialCipher cipher,Path work){this(repository,provider,cipher,work,Executors.newScheduledThreadPool(2),true);}
    public ChatExecution(ChatRepository repository,ChatProvider provider,CredentialCipher cipher,Path work,ScheduledExecutorService timers){this(repository,provider,cipher,work,timers,false);}
    private ChatExecution(ChatRepository repository,ChatProvider provider,CredentialCipher cipher,Path work,ScheduledExecutorService timers,boolean ownsTimers){this(repository,provider,cipher,work,timers,ownsTimers,null);}
    public ChatExecution(ChatRepository repository,ChatProvider provider,CredentialCipher cipher,Path work,ScheduledExecutorService timers,ToolExecutionPort fixtureTools){this(repository,provider,cipher,work,timers,false,fixtureTools);}
    private ChatExecution(ChatRepository repository,ChatProvider provider,CredentialCipher cipher,Path work,ScheduledExecutorService timers,boolean ownsTimers,ToolExecutionPort fixtureTools){
        if(fixtureTools!=null&&provider.real())throw new IllegalArgumentException("FIXTURE_TOOLS_REQUIRE_DETERMINISTIC_PROVIDER");
        this.repository=repository;this.provider=provider;this.cipher=cipher;this.work=work;this.timers=timers;this.ownsTimers=ownsTimers;this.fixtureTools=fixtureTools;
    }
    /** Compatibility for existing probes; private state always uses the fenced repository. */
    public ChatExecution(ChatRepository repository,ChatProvider provider,CredentialCipher cipher,StateStorage ignored,Path work){this(repository,provider,cipher,work);}
    public void run(Lease lease){
        synchronized(this){executionStarted=true;}
        if(!accepting())throw new ChatFailure(503,"EXECUTION_STOP_UNCONFIRMED");
        current=lease;var control=new ExecutionControl(cancellationExecutor);currentControl=control;
        Instant deadline=Instant.now().plusSeconds(120);AtomicReference<Throwable> asynchronousFailure=new AtomicReference<>();
        AtomicReference<ExecutionLedger.CallResult> accepted=new AtomicReference<>();AtomicReference<Round> acceptedRound=new AtomicReference<>();
        var batch=new Batch(lease);String terminal="FAILED",reason="MODEL_EXECUTION_FAILED";boolean completed=false,waiting=false;
        try{
            var startup=repository.executionStartup(lease);deadline=startup.deadline();
            List<Message> history=startup.history();
            Scope scope=new Scope(lease.spaceId().toString(),lease.userId().toString(),lease.conversationId().toString(),lease.taskId().toString(),lease.attemptId().toString(),lease.fence(),repository.planMode(lease));
            List<Approval> controlApprovals=repository.controlApprovals(lease);
            List<Approval> approvals=new ArrayList<>(controlApprovals);approvals.addAll(repository.toolApprovals(lease));
            boolean workspaceCapable=workspaceTools!=null&&repository.workspaceToolTask(lease);
            boolean controls=scope.planMode()||!controlApprovals.isEmpty()||(!workspaceCapable&&repository.plan(new ChatData.Actor(lease.userId(),lease.spaceId()),lease.taskId()).current()!=null);
            // Historic tasks retain their text-only Plan contract. Only a new frozen
            // workspace composition can expose business tools after Plan approval.
            List<com.agentplatform.types.harness.HarnessData.Call> businessDefinitions=controls||fixtureTools==null?List.of():RecoveryFixtureTool.DEFINITIONS.stream()
                    .filter(c->toolApprovalsEnabled||!"approval_probe".equals(c.name())).toList();
            var workspaceDefinitions=workspaceCapable&&!scope.planMode()?List.copyOf(workspaceTools.definitions(lease)):List.<ToolDefinition>of();
            var modelDefinitions=new ArrayList<ToolDefinition>();
            if(scope.planMode()||controls)modelDefinitions.addAll(PlanControls.DEFINITIONS);
            else if(workspaceCapable)modelDefinitions.add(PlanControls.DEFINITIONS.stream().filter(d->"platform_ask_user".equals(d.name())).findFirst().orElseThrow());
            modelDefinitions.addAll(workspaceDefinitions);
            modelDefinitions.addAll(businessDefinitions.stream().map(ToolDefinition::legacy).toList());
            AtomicReference<UUID> turnId=new AtomicReference<>();String frozenModelName=repository.modelName(lease);final Instant executionDeadline=deadline;
            ModelGateway metered=new ModelGateway(){
                @Override public String modelName(Scope ignored){return frozenModelName;}
                public CompletionStage<Round> complete(Scope s,List<Message> messages,List<com.agentplatform.types.harness.HarnessData.Call> tools){return stream(s,messages,tools,text->{});}
                public CompletionStage<Round> stream(Scope s,List<Message> messages,List<com.agentplatform.types.harness.HarnessData.Call> tools,Consumer<String> onText){
                    if(!tools.isEmpty()&&fixtureTools==null&&!workspaceCapable)return CompletableFuture.failedFuture(new ChatFailure(422,"MODEL_CAPABILITY_MISMATCH"));
                    return streamTyped(s,messages,tools.stream().map(ToolDefinition::legacy).toList(),onText);
                }
                @Override public CompletionStage<Round> streamTyped(Scope s,List<Message> messages,List<ToolDefinition> definitions,Consumer<String> onText){
                    var result=new CompletableFuture<Round>();AtomicReference<CompletableFuture<?>> pending=new AtomicReference<>();
                    result.whenComplete((v,e)->{if(result.isCancelled()&&pending.get()!=null)pending.get().cancel(true);});
                    var actualDefinitions=definitions.stream().filter(d->scope.planMode()||!workspaceCapable
                            ||!("platform_plan_write".equals(d.name())||"platform_plan_review".equals(d.name()))).toList();
                    physical(lease,messages,actualDefinitions,text->{if(control.closing())throw new ChatFailure(409,"TASK_CANCEL_REQUESTED");batch.append(text);onText.accept(text);},result,pending,executionDeadline,0,false,accepted,acceptedRound,control);
                    return result;
                }
            };
            RuntimeStatePort manifests=new RuntimeStatePort(){
                public void verifyRestore(Scope s,Bundle b){control.during(()->repository.validateCheckpoint(lease,startup.checkpointId(),b));}
                public boolean atomicCheckpoints(Scope s){return true;}
                public void seal(Scope s,Bundle b){throw new ChatFailure(409,"ATOMIC_CHECKPOINT_REQUIRED");}
                public List<Message> recoveryHistory(Scope s){return control.during(()->repository.checkpointHistory(lease));}
                public List<Message> checkpointHistory(Scope s){return control.during(()->repository.checkpointHistory(lease));}
                public void commitCheckpoint(Scope s,Bundle b,CheckpointDraft draft){control.during(()->{
                    if(draft.phase()!=CheckpointPhase.INPUT_READY)FixtureFaults.pause("checkpoint-before-seal");
                    repository.commitCheckpoint(lease,b,draft);
                    FixtureFaults.pause(switch(draft.phase()) {
                        case INPUT_READY -> "input-checkpoint-after-seal";
                        case MODEL_COMMITTED -> "model-checkpoint-after-seal";
                        case TOOL_COMMITTED -> "tool-checkpoint-after-seal";
                        case TERMINAL -> "terminal-checkpoint-after-seal";
                        case WAITING_INPUT -> "waiting-checkpoint-after-seal";
                        case WAITING_APPROVAL -> "approval-checkpoint-after-seal";
                    });
                    FixtureFaults.pause(draft.phase()==CheckpointPhase.INPUT_READY?"input-checkpoint-after-seal":"checkpoint-after-seal");
                });}
            };
            StateStorage attemptState=new StateStorage(){
                private void scope(Scope s){if(!scope.equals(s))throw new ChatFailure(403,"STATE_SCOPE_MISMATCH");}
                public StateSlot load(Scope s,String key){scope(s);return control.during(()->repository.state(lease,key));}
                public long compareAndSwap(Scope s,String key,long version,byte[] body){scope(s);return control.during(()->repository.stateCas(lease,key,version,body));}
                public Set<String> keys(Scope s){scope(s);return control.during(()->repository.stateKeys(lease));}
                public void delete(Scope s,String key){scope(s);control.during(()->repository.stateDelete(lease,key));}
            };
            var factory=new AgentScopeRuntimeFactory(metered,(s,c)->new OperationAuthorization(repository).decide(lease,c),
                    (s,c)->PlanControls.NAMES.contains(c.name())?CompletableFuture.completedFuture(repository.executeControl(lease,c)):
                            WorkspaceTools.NAMES.contains(c.name())&&workspaceCapable?control.during(()->{var exchange=workspaceTools.open(lease,c);control.tool(exchange);return exchange.result();}):
                            fixtureTools==null?CompletableFuture.failedFuture(new ChatFailure(422,"TOOLS_NOT_AVAILABLE")):fixtureTools.execute(s,c),
                    attemptState,manifests,new ToolCatalogPort(){
                        public List<com.agentplatform.types.harness.HarnessData.Call> definitions(){return businessDefinitions;}
                        public List<ToolDefinition> typedDefinitions(){return List.copyOf(modelDefinitions);}
                    },work);
            LoopBoundary boundary=new LoopBoundary(){
                public CompletionStage<List<Message>> beforeModel(Scope s,List<Message> ignored){return control.during(()->{
                    if(controls||workspaceCapable)repository.consumeOptional(lease);
                    var context=repository.checkpointHistory(lease);
                    var json=tools.jackson.databind.json.JsonMapper.builder().build();
                    long upperBound=json.writeValueAsString(context).getBytes(java.nio.charset.StandardCharsets.UTF_8).length+512L;
                    upperBound+=json.writeValueAsString(modelDefinitions.stream().filter(d->scope.planMode()||!workspaceCapable
                            ||!("platform_plan_write".equals(d.name())||"platform_plan_review".equals(d.name()))).map(ToolDefinition::inputSchema).toList()).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                    if(upperBound>7168)throw new ChatFailure(422,"CONTEXT_REQUIRED_TOO_LARGE");
                    turnId.set(repository.beginTurn(lease));return CompletableFuture.completedFuture(context);
                });}
                public CompletionStage<Void> afterModelCommitted(Scope s,Turn t){return control.during(()->{var call=accepted.get();if(call==null)throw new ChatFailure(409,"ACCEPTED_CALL_MISSING");repository.commitRound(lease,turnId.get(),t,call);FixtureFaults.pause("round-before-checkpoint");return CompletableFuture.completedFuture(null);});}
                public CompletionStage<Void> beforeControlBatch(Scope s,List<com.agentplatform.types.harness.HarnessData.Call> calls){
                    return control.during(()->{if(calls.stream().anyMatch(c->PlanControls.NAMES.contains(c.name())))repository.stageControls(lease,calls);else {if(workspaceCapable&&calls.stream().anyMatch(c->WorkspaceTools.NAMES.contains(c.name())))workspaceTools.prepare(lease,calls);repository.stageToolApprovals(lease,calls);}return CompletableFuture.completedFuture(null);});
                }
                public CompletionStage<Void> beforeWaiting(Scope s,List<com.agentplatform.types.harness.HarnessData.Call> calls){
                    return control.during(()->{if(calls.stream().anyMatch(c->PlanControls.NAMES.contains(c.name())))repository.stageControls(lease,calls);else repository.stageToolApprovals(lease,calls);return CompletableFuture.completedFuture(null);});
                }
                public CompletionStage<Void> beforeTool(Scope s,com.agentplatform.types.harness.HarnessData.Call c){return control.during(()->{
                    if(fixtureTools==null&&!controls&&!workspaceCapable)throw new ChatFailure(422,"TOOLS_NOT_AVAILABLE");
                    if(workspaceCapable&&WorkspaceTools.NAMES.contains(c.name()))workspaceTools.admit(lease,c);else repository.startTool(lease,c);
                    FixtureFaults.pause("tool-after-start");return CompletableFuture.completedFuture(null);
                });}
                public CompletionStage<Void> afterToolCommitted(Scope s,com.agentplatform.types.harness.HarnessData.Call c,Observation o){return control.during(()->{
                    if(fixtureTools==null&&!controls&&!workspaceCapable)throw new ChatFailure(422,"TOOLS_NOT_AVAILABLE");
                    FixtureFaults.pause("tool-before-result");repository.commitTool(lease,c,o);FixtureFaults.pause("tool-after-result");return CompletableFuture.completedFuture(null);
                });}
                public CompletionStage<Completion> beforeStop(Scope s,Message message){return CompletableFuture.completedFuture(message.text().isBlank()||scope.planMode()?Completion.INCOMPLETE:Completion.PASS);}
            };
            PreparedRun prepared=factory.prepare(new Request(scope,repository.executionBundle(lease),startup.restoring()?List.of():List.of(history.getLast()),startup.restoring(),approvals,startup.nextTurn()),boundary);active=prepared;control.run(prepared);
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
            batch.flush();var completion=((Outcome)outcome).completion();
            if(completion==Completion.WAITING_INPUT||completion==Completion.WAITING_APPROVAL){waiting=true;terminal="WAITING_INPUT";reason=null;completed=true;}
            else {if(completion!=Completion.PASS)throw new ChatFailure(503,"MODEL_INCOMPLETE");terminal="SUCCEEDED";reason=null;completed=true;}
        }catch(Throwable failure){
            Throwable error=failure;
            for(Throwable cause=failure;cause!=null;cause=cause.getCause()){
                if(cause instanceof IllegalArgumentException&&"TOOL_BATCH_UNSUPPORTED".equals(cause.getMessage())){error=new ChatFailure(422,"TOOL_BATCH_UNSUPPORTED");break;}
                if(cause instanceof ChatFailure||cause instanceof TimeoutException){error=cause;break;}
            }
            if(asynchronousFailure.get()!=null)error=asynchronousFailure.get();
            reason=error instanceof ChatFailure f?f.code():error instanceof TimeoutException?"PROVIDER_TIMEOUT":"MODEL_EXECUTION_FAILED";
            terminal="TASK_CANCEL_REQUESTED".equals(reason)?"CANCELLED":"CONTEXT_REQUIRED_TOO_LARGE".equals(reason)?"BLOCKED":"FAILED";
        }finally{
            boolean quiet=control.quiesce(!completed);
            try{
                if(quiet){
                    var value=accepted.get();var round=acceptedRound.get();
                    CancellationLedger.KnownUsage usage=value!=null&&value.usageKnown()&&round!=null?new CancellationLedger.KnownUsage(value.call(),round,value.requestId(),value.real()):null;
                    String receipt=control.receipt(lease.taskId()+":"+lease.attemptId()+":"+lease.ownerId()+":"+lease.fence());
                    if(waiting)repository.waitingStopped(lease,receipt);else repository.stopped(lease,terminal,reason,usage,receipt);
                }else{unavailable.set(true);repository.stopUnconfirmed(lease);}
            }catch(Throwable lost){unavailable.set(true);System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,"Attempt requires stopped-owner reconciliation; terminationConfirmed="+quiet);}
            active=null;current=null;currentControl=null;
        }
    }
    private void physical(Lease l,List<Message> messages,List<ToolDefinition> definitions,Consumer<String> text,CompletableFuture<Round> result,AtomicReference<CompletableFuture<?>> pending,Instant deadline,int retries,boolean repaired,AtomicReference<ExecutionLedger.CallResult> accepted,AtomicReference<Round> acceptedRound,ExecutionControl control){
        if(result.isDone())return;
        try(var ticket=control.enter()){
            if(Instant.now().isAfter(deadline)){result.completeExceptionally(new ChatFailure(504,"PROVIDER_TIMEOUT"));return;}
            ChatData.Call call=repository.beginCall(l,provider.real());String key;
            try{key=cipher.decrypt(repository.credential(l,call.id()));if(provider.real()&&acceptanceRun!=null)repository.permitAcceptanceCall(l,call,acceptanceRun);repository.startCall(l,call,provider.real());}
            catch(Throwable error){try{repository.settle(l,call,null,null,"CALL_NOT_SENT",false);}catch(Exception ignored){}result.completeExceptionally(error);return;}
            AtomicBoolean emitted=new AtomicBoolean();CompletableFuture<ChatProvider.Result> future;
            try{var exchange=(definitions.isEmpty()?provider.open(repository.modelName(l),key,messages,value->{emitted.set(true);text.accept(value);}):provider.openTyped(repository.modelName(l),key,messages,definitions,value->{emitted.set(true);text.accept(value);}));control.exchange(exchange);future=exchange.result().toCompletableFuture();}
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
                            control.timer(timers.schedule(()->physical(l,messages,definitions,text,result,pending,deadline,retries+1,repaired,accepted,acceptedRound,control),retries==0?1:3,TimeUnit.SECONDS));return;
                        }result.completeExceptionally(cause);return;
                    }
                    boolean incompleteTransport="unknown".equals(value.round().finishReason());
                    boolean empty=value.round().text().isBlank()&&value.round().calls().isEmpty()&&!emitted.get();
                    boolean supported="stop".equals(value.round().finishReason())||((fixtureTools!=null||!definitions.isEmpty())&&"tool_calls".equals(value.round().finishReason()));
                    if(!supported||empty){
                        repository.settle(l,call,value.usageKnown()?value.round():null,value.requestId(),value.usageKnown()?null:incompleteTransport?"PROVIDER_REQUEST_FAILED":"PROVIDER_USAGE_MISSING",provider.real());
                        if(!"stop".equals(value.round().finishReason())){result.completeExceptionally(new ChatFailure(503,"PROVIDER_TRUNCATED"));return;}
                        if(!repaired){physical(l,messages,definitions,text,result,pending,deadline,retries,true,accepted,acceptedRound,control);return;}
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
