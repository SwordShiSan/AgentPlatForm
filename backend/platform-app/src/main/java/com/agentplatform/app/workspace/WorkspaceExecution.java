package com.agentplatform.app.workspace;

import com.agentplatform.domain.workspace.*;
import com.agentplatform.domain.workspace.WorkspaceData.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One HTTP effect attempt, then query only. Timers never manufacture a stopped owner. */
public final class WorkspaceExecution {
    private final WorkspaceRepository repository;
    private final RunnerControlPort runner;
    private final ScheduledExecutorService timers;
    public WorkspaceExecution(WorkspaceRepository repository,RunnerControlPort runner,ScheduledExecutorService timers) {
        this.repository=repository;this.runner=runner;this.timers=timers;
    }
    public void run(Command command) {
        AtomicBoolean valid=new AtomicBoolean(true);
        var renewal=timers.scheduleAtFixedRate(()->{try{repository.renew(command);}catch(RuntimeException failure){valid.set(false);}},10,10,TimeUnit.SECONDS);
        try {
            repository.beforeCall(command);
            Receipt result=null;
            try{result=runner.provision(command);}catch(RuntimeException ambiguous){ /* Query stable command; never POST again. */ }
            while(valid.get()&&Instant.now().isBefore(command.deadline().minusSeconds(5))) {
                if(result!=null&&!result.state().equals("RUNNING")) { repository.complete(command,result);return; }
                TimeUnit.MILLISECONDS.sleep(500);
                try{result=runner.query(command);}catch(RuntimeException unavailable){result=null;}
            }
            repository.uncertain(command,"RUNNER_RESULT_UNKNOWN");
        } catch(InterruptedException interrupted){Thread.currentThread().interrupt();block(command);}
        catch(RuntimeException failure){block(command);}
        finally{renewal.cancel(false);}
    }
    private void block(Command command) {
        try{repository.uncertain(command,"RUNNER_RESULT_UNKNOWN");}
        catch(RuntimeException retained){ /* No authority to change expired/revoked owners; preserve reservation. */ }
    }
}
