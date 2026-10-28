package com.agentplatform.app.chat;

import com.agentplatform.app.files.*;
import com.agentplatform.domain.chat.*;
import com.agentplatform.domain.chat.ChatData.Lease;
import com.agentplatform.domain.files.*;
import com.agentplatform.domain.harness.HarnessPorts.ToolExchange;
import com.agentplatform.domain.workspace.*;
import com.agentplatform.domain.workspace.EnvironmentUseData.*;
import com.agentplatform.types.harness.HarnessData.*;
import com.agentplatform.infrastructure.workspace.RunnerSignatures;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Composes existing file/MQ/runner authorities behind the one SDK tool port. */
public final class WorkspaceToolsApplicationService implements WorkspaceToolRuntime {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final ChatRepository chat;
    private final WorkspaceFileToolService fileTools;
    private final FileRepository files;
    private final AssetStore assets;
    private final EnvironmentLifecycleRepository environments;
    private final EnvironmentUseRepository uses;
    private final WorkspaceShellRunnerPort runner;
    private final WorkspaceToolJobs jobs;
    private final TransactionTemplate transactions;
    private final Executor executor;
    private final ScheduledExecutorService timers;
    private final String image,policy;
    // Only the process that committed a fresh admission may issue its one POST.
    private final ConcurrentMap<UUID,Admission> fresh=new ConcurrentHashMap<>();
    public WorkspaceToolsApplicationService(ChatRepository chat,FileRepository files,AssetStore assets,
            EnvironmentLifecycleRepository environments,EnvironmentUseRepository uses,WorkspaceShellRunnerPort runner,
            WorkspaceToolJobs jobs,TransactionTemplate transactions,Executor executor,ScheduledExecutorService timers,String image,String policy) {
        this.chat=chat;this.files=files;this.assets=assets;this.environments=environments;this.uses=uses;this.runner=runner;
        this.jobs=jobs;this.transactions=transactions;this.executor=executor;this.timers=timers;this.image=image;this.policy=policy;
        this.fileTools=new WorkspaceFileToolService(chat,files,assets,transactions);
    }
    private static WorkspaceData.Actor actor(Lease l){return new WorkspaceData.Actor(l.userId(),l.spaceId());}
    private static FileData.Actor fileActor(Lease l){return new FileData.Actor(l.userId(),l.spaceId());}
    public List<ToolDefinition> definitions(Lease lease){return chat.workspaceToolTask(lease)?WorkspaceTools.DEFINITIONS:List.of();}
    public void prepare(Lease lease,List<Call> calls){
        if(calls.size()==1&&"workspace_shell".equals(calls.getFirst().name())&&!chat.planMode(lease)) {
            var permissions=chat.permissions(new ChatData.Actor(lease.userId(),lease.spaceId()),lease.taskId());
            if(permissions.workspaceId()!=null) {
                UUID workspace=UUID.fromString(permissions.workspaceId());
                while(true) {
                    chat.check(lease);
                    var state=environments.get(actor(lease),workspace);
                    if(Set.of("BLOCKED","ERROR").contains(state.state()))throw new ChatFailure(409,"ENVIRONMENT_PREPARATION_FAILED");
                    if(state.sleepAllowed()||state.wakeAllowed()||state.prepareAllowed())break;
                    try{Thread.sleep(100);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new ChatFailure(409,"TASK_CANCEL_REQUESTED");}
                }
            }
        }
        chat.prepareWorkspaceTools(lease,calls,image,policy);
    }
    public void admit(Lease lease,Call call) {
        if(!"workspace_shell".equals(call.name())){fileTools.admit(lease,call);return;}
        chat.startTool(lease,call);
    }
    private void activate(Lease lease,WorkspaceToolLedger.Intent intent,EnvironmentPreparationRepository.Snapshot snapshot) {
        String hash=RunnerSignatures.sha256((intent.operationId()+"\n"+intent.imageId()+"\n"+intent.policyHash()).getBytes(StandardCharsets.UTF_8));
        UUID operation;
        if(snapshot.wakeAllowed())operation=environments.admit(actor(lease),intent.workspaceId(),intent.activationRequestId(),hash,snapshot.version(),EnvironmentLifecycleRepository.Intent.WAKE).operationId();
        else if(snapshot.prepareAllowed())operation=environments.prepare(actor(lease),intent.workspaceId(),intent.activationRequestId(),hash,snapshot.version(),false).operationId();
        else throw new ChatFailure(409,"ENVIRONMENT_NOT_READY");
        jobs.bindActivation(intent.operationId(),operation);
    }
    public ToolExchange open(Lease lease,Call call) {
        if(!"workspace_shell".equals(call.name()))return new WorkspaceFileToolExchange(chat,lease,call,cancelled->fileTools.run(lease,call,cancelled),executor,timers);
        return new ShellExchange(lease,call);
    }
    private void check(Lease lease,BooleanSupplier cancelled){if(cancelled.getAsBoolean())throw new ChatFailure(409,"TASK_CANCEL_REQUESTED");chat.check(lease);}
    private Command ready(Lease lease,Call call,BooleanSupplier cancelled) throws InterruptedException {
        while(true) {
            check(lease,cancelled);
            var command=transactions.execute(status->{
                jobs.lockQuota(lease.userId(),lease.spaceId());chat.check(lease);var intent=chat.workspaceToolIntent(lease,call);
                var state=environments.get(actor(lease),intent.workspaceId());
                if(Set.of("BLOCKED","ERROR").contains(state.state()))throw new ChatFailure(409,"ENVIRONMENT_PREPARATION_FAILED");
                if(!"READY".equals(state.state())) {
                    if(state.wakeAllowed()||state.prepareAllowed())activate(lease,intent,state);
                    return null;
                }
                if(!state.sleepAllowed())return null;
                var physical=jobs.environment(lease.spaceId(),intent.workspaceId());
                if(physical==null||!image.equals(physical.imageId())||!policy.equals(physical.policyHash()))throw new ChatFailure(409,"ENVIRONMENT_POLICY_CHANGED");
                return uses.admitShell(actor(lease),intent.workspaceId(),intent.operationId(),lease.ownerId(),chat.workspaceShellRequest(lease,call));
            });
            if(command!=null){if(command.fresh())fresh.put(command.command().id(),command);return command.command();}
            Thread.sleep(100);
        }
    }
    private Observation shell(Lease lease,Call call,AtomicBoolean cancelled) throws Exception {
        Command command=ready(lease,call,cancelled::get);
        var renewal=timers.scheduleAtFixedRate(()->{try{uses.renew(command);}catch(RuntimeException lost){cancelled.set(true);}},10,10,TimeUnit.SECONDS);
        try {
            Admission ticket=fresh.remove(command.id());
            if(ticket!=null){try{runner.executeShell(command);}catch(RuntimeException responseLost){/* Query the exact original command, never POST again. */}}
            while(Instant.now().isBefore(command.deadline())) {
                if(cancelled.get()){try{uses.cancel(actor(lease),command.id());}catch(RuntimeException revoked){/* Runner parent checks stop the original generation. */}}
                var receipt=uses.receipt(command);
                if(receipt.resultHash()!=null){
                    var fact=uses.committedFact(command);
                    if(!"KNOWN".equals(fact.resultState()))throw new ChatFailure(409,"WORKSPACE_TOOL_UNKNOWN");
                    check(lease,cancelled::get);
                    UUID operation=((WorkspaceShellData.Request)command.request()).parent().operationId();
                    transactions.executeWithoutResult(status->{jobs.lockQuota(lease.userId(),lease.spaceId());chat.check(lease);jobs.observeStoppedSource(operation,command.id());});
                    byte[] stdout=fact.output().stdout().getBytes(StandardCharsets.UTF_8),stderr=fact.output().stderr().getBytes(StandardCharsets.UTF_8);
                    byte[] combined=new byte[stdout.length+stderr.length];System.arraycopy(stdout,0,combined,0,stdout.length);System.arraycopy(stderr,0,combined,stdout.length,stderr.length);
                    String key="spaces/"+lease.spaceId()+"/tool-result/"+operation+"/content",hash=RunnerSignatures.sha256(combined);
                    var stored=storeResult(lease,cancelled::get,key,hash,combined);
                    if(!key.equals(stored.key())||stored.bytes()!=combined.length||!hash.equals(stored.sha256()))throw new ChatFailure(503,"TOOL_RESULT_IDENTITY_MISMATCH");
                    transactions.executeWithoutResult(status->{jobs.lockQuota(lease.userId(),lease.spaceId());chat.check(lease);jobs.settleResult(operation,key,hash,stdout.length,stderr.length);});
                    files.admitCaptures(fileActor(lease),operation);
                    List<WorkspaceToolJobs.Artifact> outputs;
                    while(true){
                        check(lease,cancelled::get);outputs=transactions.execute(status->jobs.artifacts(operation));
                        if(outputs.stream().anyMatch(o->"RECONCILING".equals(o.status())))throw new ChatFailure(409,"WORKSPACE_TOOL_UNKNOWN");
                        if(outputs.stream().allMatch(o->Set.of("COMMITTED","FAILED","RELEASED").contains(o.status())))break;
                        Thread.sleep(100);
                    }
                    uses.complete(command);
                    try{runner.queryShell(command);}catch(RuntimeException cleanupPending){/* Durable exact stop remains authoritative. */}
                    var result=new LinkedHashMap<String,Object>();result.put("operationId",operation);result.put("exitCode",fact.output().exitCode());
                    result.put("truncated",fact.output().truncated());result.put("stdout",summary(fact.output().stdout(),3000));result.put("stderr",summary(fact.output().stderr(),1000));
                    result.put("resultAvailable",true);
                    // Version links are also available through the authorized operation result.
                    result.put("files",outputs.stream().filter(o->"COMMITTED".equals(o.status())).map(o->Map.of("fileId",o.fileId(),"versionId",o.versionId(),"sha256",o.sha256())).toList());
                    boolean failed=fact.output().exitCode()!=0||outputs.stream().anyMatch(o->!"COMMITTED".equals(o.status()));
                    if(failed)result.put("code",fact.output().exitCode()!=0?"SHELL_EXIT_NONZERO":"OUTPUT_CAPTURE_FAILED");
                    String body=JSON.writeValueAsString(result);
                    if(body.getBytes(StandardCharsets.UTF_8).length>8192){result.put("files",List.of());body=JSON.writeValueAsString(result);}
                    while(body.getBytes(StandardCharsets.UTF_8).length>8192){result.put("stdout",summary(result.get("stdout").toString(),result.get("stdout").toString().getBytes(StandardCharsets.UTF_8).length/2));result.put("stderr",summary(result.get("stderr").toString(),result.get("stderr").toString().getBytes(StandardCharsets.UTF_8).length/2));body=JSON.writeValueAsString(result);}
                    return new Observation(call.id(),call.name(),body,failed?OperationStatus.FAILED:OperationStatus.SUCCEEDED);
                }
                try{runner.queryShell(command);}catch(RuntimeException pending){/* Original result only. */}
                Thread.sleep(100);
            }
            throw new ChatFailure(409,"WORKSPACE_TOOL_STOP_UNCONFIRMED");
        }finally{renewal.cancel(false);convergeStopped(command);}
    }
    private AssetStore.Identity storeResult(Lease lease,BooleanSupplier cancelled,String key,String hash,byte[] bytes)throws InterruptedException {
        while(true){
            check(lease,cancelled);
            try{return assets.put(key,bytes.length,hash,new ByteArrayInputStream(bytes));}
            catch(FileFailure busy){if(busy.status()!=423||!"FILE_TRANSFER_BUSY".equals(busy.getMessage()))throw busy;Thread.sleep(100);}
        }
    }
    private void convergeStopped(Command command) {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        UUID operation=((WorkspaceShellData.Request)command.request()).parent().operationId();
        do {
            try {
                var receipt=uses.receipt(command);
                if(receipt.resultHash()!=null) {
                    var fact=uses.committedFact(command);
                    if(fact.stop()!=null&&fact.stop().execStopped()&&fact.stop().callbacksStopped()) {
                        transactions.executeWithoutResult(status->jobs.observeStoppedSource(operation,command.id()));
                        if(Boolean.TRUE.equals(transactions.execute(status->jobs.childrenStopped(operation)))) {
                            uses.complete(command);try{runner.queryShell(command);}catch(RuntimeException cleanupPending){}return;
                        }
                    }
                }
                runner.queryShell(command);
            }catch(RuntimeException unavailable){/* Keep original children and slots when stopping is unproven. */}
            try{Thread.sleep(100);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();return;}
        }while(System.nanoTime()<until);
    }
    private static String summary(String text,int bytes){StringBuilder out=new StringBuilder();int used=0;for(var iterator=text.codePoints().iterator();iterator.hasNext();){int cp=iterator.nextInt();int n=new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;if(used+n>bytes)break;out.appendCodePoint(cp);used+=n;}return out.toString();}
    private final class ShellExchange implements ToolExchange {
        private final CompletableFuture<Observation> result=new CompletableFuture<>();
        private final CompletableFuture<Void> terminated=new CompletableFuture<>();
        private final AtomicBoolean cancelled=new AtomicBoolean();
        private final Lease lease;private final Call call;private final UUID operation;
        ShellExchange(Lease lease,Call call){this.lease=lease;this.call=call;this.operation=chat.workspaceToolIntent(lease,call).operationId();
            executor.execute(()->{
                Observation value=null;Throwable problem=null;
                try{value=shell(lease,call,cancelled);}catch(Throwable failed){problem=failed;}
                try{
                    if(!Boolean.TRUE.equals(transactions.execute(status->jobs.childrenStopped(operation))))throw new ChatFailure(409,"WORKSPACE_TOOL_STOP_UNCONFIRMED");
                    terminated.complete(null);
                }catch(Throwable unproven){terminated.completeExceptionally(unproven);if(problem==null)problem=unproven;}
                if(problem==null)result.complete(value);else result.completeExceptionally(problem);
            });
        }
        public CompletionStage<Observation> result(){return result;}
        public CompletionStage<Void> terminated(){return terminated;}
        public void cancel(){if(cancelled.compareAndSet(false,true)){chat.requestWorkspaceToolStop(lease,call,WorkspaceToolLedger.StopReason.CANCEL);result.completeExceptionally(new ChatFailure(409,"TASK_CANCEL_REQUESTED"));}}
    }
}
