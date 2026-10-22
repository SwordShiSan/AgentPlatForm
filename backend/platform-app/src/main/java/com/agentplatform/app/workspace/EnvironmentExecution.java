package com.agentplatform.app.workspace;

import com.agentplatform.domain.workspace.*;
import com.agentplatform.domain.workspace.EnvironmentExecutionData.*;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Once durable handoff commits: one POST, then original-command queries only. */
public final class EnvironmentExecution implements AutoCloseable {
    private final EnvironmentExecutionRepository repository;
    private final EnvironmentRunnerPort runner;
    private final ScheduledExecutorService timers;
    private final AtomicBoolean accepting=new AtomicBoolean(true);
    public EnvironmentExecution(EnvironmentExecutionRepository repository,EnvironmentRunnerPort runner,ScheduledExecutorService timers) {
        this.repository=repository;this.runner=runner;this.timers=timers;
    }
    public void run(Command command) {
        var valid=new AtomicBoolean(true);
        var renewal=timers.scheduleAtFixedRate(()->{try{repository.renew(command);}catch(RuntimeException lost){valid.set(false);}},10,10,TimeUnit.SECONDS);
        try {
            if(!accepting.get()) {block(command);return;}
            repository.beforeCall(command);
            Receipt receipt=null;
            try {receipt=runner.prepareEnvironment(command);}catch(RuntimeException unknown) { /* Original command is queried below. */ }
            while(accepting.get()&&valid.get()&&Instant.now().isBefore(command.deadline().minusSeconds(5))) {
                if(receipt!=null&&!"RUNNING".equals(receipt.state())) {repository.complete(command,receipt);return;}
                TimeUnit.MILLISECONDS.sleep(500);
                try {receipt=runner.queryEnvironment(command);}catch(RuntimeException unknown) {receipt=null;}
            }
            block(command);
        } catch(InterruptedException interrupted) {Thread.currentThread().interrupt();block(command);}
        catch(RuntimeException unknown) {block(command);}
        finally {renewal.cancel(false);}
    }
    private void block(Command command) {
        try {repository.uncertain(command,"SANDBOX_RESULT_UNKNOWN");}
        catch(RuntimeException noAuthority) { /* No slot release, replacement owner or alternate execution. */ }
    }
    @Override public void close() {accepting.set(false);}
}
