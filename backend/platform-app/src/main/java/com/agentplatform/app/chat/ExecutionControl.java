package com.agentplatform.app.chat;

import com.agentplatform.domain.chat.ChatFailure;
import com.agentplatform.domain.chat.ChatProvider.Exchange;
import com.agentplatform.domain.harness.HarnessPorts.PreparedRun;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One attempt's bounded shutdown barrier, independent of its result future. */
public final class ExecutionControl {
    private final Executor cancellationExecutor;
    private final Phaser work=new Phaser(1);
    private final List<Exchange> exchanges=new CopyOnWriteArrayList<>();
    private final List<Future<?>> timers=new CopyOnWriteArrayList<>();
    private final AtomicBoolean closing=new AtomicBoolean(),cancelled=new AtomicBoolean();
    private final CompletableFuture<Void> cancellation=new CompletableFuture<>();
    private final List<CompletableFuture<Void>> shutdownActions=new CopyOnWriteArrayList<>();
    private volatile PreparedRun run;
    private volatile long deadline;
    private volatile boolean quiescent;
    public ExecutionControl(Executor cancellationExecutor) { this.cancellationExecutor=cancellationExecutor; }
    public void run(PreparedRun value) { run=value;if(cancelled.get())dispatch(value::cancel); }
    public void exchange(Exchange value) { exchanges.add(value);if(closing.get())dispatch(value::cancel); }
    public void timer(Future<?> value) { timers.add(value);if(closing.get())value.cancel(false); }
    public boolean closing() { return closing.get(); }
    public CompletionStage<Void> cancellation() { return cancellation; }
    public AutoCloseable enter() {
        if(closing.get()||work.register()<0)throw new ChatFailure(409,"TASK_CANCEL_REQUESTED");
        if(closing.get()){work.arriveAndDeregister();throw new ChatFailure(409,"TASK_CANCEL_REQUESTED");}
        return work::arriveAndDeregister;
    }
    private void dispatch(Runnable action) {
        var done=new CompletableFuture<Void>();shutdownActions.add(done);
        try { cancellationExecutor.execute(()->{try{action.run();done.complete(null);}catch(Throwable failure){done.completeExceptionally(failure);}}); }
        catch(RejectedExecutionException failure){done.completeExceptionally(failure);}
    }
    public void cancel() {
        if(cancelled.compareAndSet(false,true)) {
            deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);closing.set(true);cancellation.complete(null);
            timers.forEach(timer->timer.cancel(false));
            dispatch(()->{if(run!=null)run.cancel();for(var exchange:exchanges)exchange.cancel();});
        }
    }
    public boolean quiesce(boolean cancel) {
        if(cancel)cancel();
        if(deadline==0)deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        closing.set(true);timers.forEach(timer->timer.cancel(false));
        try {
            int phase=work.arriveAndDeregister();
            work.awaitAdvanceInterruptibly(phase,remaining(),TimeUnit.NANOSECONDS);
            // No work can now add a provider or retry. The lists are stable.
            for(var action:shutdownActions)action.get(remaining(),TimeUnit.NANOSECONDS);
            if(run!=null)run.terminated().toCompletableFuture().get(remaining(),TimeUnit.NANOSECONDS);
            for(var exchange:exchanges)exchange.terminated().toCompletableFuture().get(remaining(),TimeUnit.NANOSECONDS);
            quiescent=true;return true;
        }catch(Exception unconfirmed){return false;}
    }
    private long remaining() throws TimeoutException {
        long value=deadline-System.nanoTime();if(value<=0)throw new TimeoutException("STOP_NOT_CONFIRMED");return value;
    }
    public String receipt(String scope) {
        if(!quiescent)throw new ChatFailure(409,"EXECUTION_STOP_NOT_CONFIRMED");
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((scope+":SDK_PROVIDER_CALLBACK_TIMERS_QUIESCED:"+exchanges.size()).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception impossible){throw new IllegalStateException(impossible);}
    }
}
