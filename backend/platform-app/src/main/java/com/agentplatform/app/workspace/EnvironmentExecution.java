package com.agentplatform.app.workspace;

import com.agentplatform.domain.workspace.*;
import com.agentplatform.domain.workspace.EnvironmentExecutionData.*;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Once durable handoff commits: one POST, then original-command queries only. */
public final class EnvironmentExecution implements AutoCloseable {
    private static final System.Logger LOG=System.getLogger(EnvironmentExecution.class.getName());
    private final EnvironmentExecutionRepository repository;
    private final EnvironmentRunnerPort runner;
    private final ScheduledExecutorService timers;
    private final AtomicBoolean accepting=new AtomicBoolean(true);
    public EnvironmentExecution(EnvironmentExecutionRepository repository,EnvironmentRunnerPort runner,ScheduledExecutorService timers) {
        this.repository=repository;this.runner=runner;this.timers=timers;
    }
    public void run(Command command) {
        var valid=new AtomicBoolean(true);
        var renewal=timers.scheduleAtFixedRate(()->{try{repository.renew(command);}catch(RuntimeException lost){diagnose(command,"RENEWAL",lost);valid.set(false);}},10,10,TimeUnit.SECONDS);
        try {
            if(!accepting.get()) {block(command);return;}
            repository.beforeCall(command);
            Receipt receipt=null;
            try {receipt=runner.prepareEnvironment(command);}catch(RuntimeException unknown) {diagnose(command,"INITIAL_RESPONSE",unknown); /* Query only the original command. */ }
            while(accepting.get()&&valid.get()&&Instant.now().isBefore(command.deadline().minusSeconds(5))) {
                if(receipt!=null&&!"RUNNING".equals(receipt.state())) {repository.complete(command,receipt);return;}
                TimeUnit.MILLISECONDS.sleep(500);
                try {receipt=runner.queryEnvironment(command);}catch(RuntimeException unknown) {receipt=null;}
            }
            block(command);
        } catch(InterruptedException interrupted) {Thread.currentThread().interrupt();block(command);}
        catch(RuntimeException unknown) {diagnose(command,"EXECUTION",unknown);block(command);}
        finally {renewal.cancel(false);}
    }
    private static void diagnose(Command command,String phase,RuntimeException failure) {
        String code=failure instanceof WorkspaceFailure?failure.getMessage():"INTERNAL_ERROR";
        String rootCause=failure.getClass().getSimpleName();
        for(Throwable cause=failure;cause!=null;cause=cause.getCause())rootCause=cause.getClass().getSimpleName();
        LOG.log(System.Logger.Level.WARNING,"Environment operation="+command.operationId()+" phase="+phase+" category="+failure.getClass().getSimpleName()+" code="+code+" rootCause="+rootCause);
    }
    private void block(Command command) {
        try {repository.uncertain(command,"SANDBOX_RESULT_UNKNOWN");}
        catch(RuntimeException noAuthority) { /* No slot release, replacement owner or alternate execution. */ }
    }
    @Override public void close() {accepting.set(false);}
}
