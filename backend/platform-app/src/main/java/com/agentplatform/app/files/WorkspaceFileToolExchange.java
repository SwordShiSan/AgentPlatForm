package com.agentplatform.app.files;

import com.agentplatform.domain.chat.*;
import com.agentplatform.domain.chat.ChatData.Lease;
import com.agentplatform.domain.harness.HarnessPorts.ToolExchange;
import com.agentplatform.types.harness.HarnessData.Call;
import com.agentplatform.types.harness.HarnessData.Observation;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Transport completion, cancel intent and physical termination are distinct facts. */
public final class WorkspaceFileToolExchange implements ToolExchange {
    @FunctionalInterface public interface Work { Observation run(BooleanSupplier cancelled) throws Exception; }
    private final ChatRepository chat;
    private final Lease lease;
    private final Call call;
    private final CompletableFuture<Observation> result=new CompletableFuture<>();
    private final CompletableFuture<Void> terminated=new CompletableFuture<>();
    private final AtomicBoolean cancelled=new AtomicBoolean(),localExited=new AtomicBoolean();
    private final long observationDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(135);
    private volatile Observation observation;
    private volatile Throwable failure;
    private final ScheduledFuture<?> observer;

    public WorkspaceFileToolExchange(ChatRepository chat,Lease lease,Call call,Work work,Executor executor,ScheduledExecutorService timers) {
        this.chat=Objects.requireNonNull(chat);this.lease=Objects.requireNonNull(lease);this.call=Objects.requireNonNull(call);
        if(!java.util.Set.of("workspace_list","workspace_read","workspace_write").contains(call.name()))throw new IllegalArgumentException("FILE_TOOL_REQUIRED");
        // A bounded observer only reads original facts. It never starts/replays a job.
        observer=timers.scheduleWithFixedDelay(this::observe,100,100,TimeUnit.MILLISECONDS);
        terminated.whenComplete((ignored,problem)->observer.cancel(false));
        try {
            executor.execute(()->{
                try {
                    if(cancelled.get())throw new ChatFailure(409,"TASK_CANCEL_REQUESTED");
                    observation=Objects.requireNonNull(work.run(cancelled::get));
                }catch(Throwable problem){failure=problem;}
                finally{localExited.set(true);observe();}
            });
        }catch(RuntimeException unavailable){failure=unavailable;localExited.set(true);observe();}
    }
    private void observe() {
        if(terminated.isDone()||!localExited.get())return;
        try {
            boolean stopped=!"workspace_write".equals(call.name())||chat.workspaceFileStopped(lease,call);
            if(stopped) {
                terminated.complete(null);
                if(failure!=null)result.completeExceptionally(failure);
                else if(cancelled.get())result.completeExceptionally(new ChatFailure(409,"TASK_CANCEL_REQUESTED"));
                else result.complete(observation);
                return;
            }
        }catch(RuntimeException pending){/* Authorization loss does not invent physical termination. */}
        if(System.nanoTime()>=observationDeadline) {
            var unproven=new ChatFailure(409,"FILE_TOOL_STOP_UNCONFIRMED");
            terminated.completeExceptionally(unproven);result.completeExceptionally(unproven);
        }
    }
    @Override public CompletionStage<Observation> result(){return result;}
    @Override public CompletionStage<Void> terminated(){return terminated;}
    @Override public void cancel() {
        if(terminated.isDone()&&!terminated.isCompletedExceptionally())return;
        if(cancelled.compareAndSet(false,true)) {
            // Do not interrupt/close a Future and infer that synchronous S3 IO ended.
            chat.requestWorkspaceToolStop(lease,call,WorkspaceToolLedger.StopReason.CANCEL);
            result.completeExceptionally(new ChatFailure(409,"TASK_CANCEL_REQUESTED"));
        }
        observe();
    }
}
