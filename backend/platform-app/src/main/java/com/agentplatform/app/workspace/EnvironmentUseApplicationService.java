package com.agentplatform.app.workspace;

import com.agentplatform.domain.workspace.*;
import com.agentplatform.domain.workspace.EnvironmentUseData.*;
import com.agentplatform.domain.workspace.WorkspaceData.Actor;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Protected application port. Not registered in the public API or AgentScope Toolkit. */
public final class EnvironmentUseApplicationService {
    private final EnvironmentUseRepository repository;
    private final EnvironmentUseRunnerPort runner;
    private final ScheduledExecutorService timers;
    public EnvironmentUseApplicationService(EnvironmentUseRepository repository,EnvironmentUseRunnerPort runner,ScheduledExecutorService timers) {
        this.repository=repository;this.runner=runner;this.timers=timers;
    }
    public Fact execute(Actor actor,UUID workspace,UUID request,Request body) {
        return executeAdmitted(actor,repository.admit(actor,workspace,request,UUID.randomUUID(),body));
    }
    Fact executeAdmitted(Actor actor,Admission admitted) {
        var command=admitted.command();
        if(!admitted.fresh()) {
            // Lost response reads the original fact. It cannot become a new executor.
            var receipt=repository.receipt(command);
            if(receipt.resultHash()!=null) {
                if(!java.util.Set.of("COMMITTED","FAILED","STOPPED").contains(receipt.state()))repository.complete(command);
                return repository.result(actor,command.id());
            }
            throw new WorkspaceFailure(409,"ENVIRONMENT_USE_RECONCILIATION_REQUIRED");
        }
        var live=new AtomicBoolean(true);
        var renewal=timers.scheduleAtFixedRate(()->{try{repository.renew(command);}catch(RuntimeException lost){live.set(false);}},10,10,TimeUnit.SECONDS);
        Receipt receipt=null;
        try {
            try {receipt=runner.executeUse(command);}catch(RuntimeException unknown) { /* One POST only, then query original use. */ }
            while(Instant.now().isBefore(command.deadline())) {
                // Immutable DB facts remain authoritative when HTTP response/runner
                // is lost. This reads a result, never starts work from the database.
                if(receipt==null||receipt.resultHash()==null)receipt=repository.receipt(command);
                if(receipt!=null&&receipt.resultHash()!=null) {
                    if(!receipt.resultHash().equals(repository.receipt(command).resultHash()))throw new WorkspaceFailure(503,"RUNNER_RECEIPT_MISMATCH");
                    repository.complete(command);
                    try {runner.queryUse(command);}catch(RuntimeException pendingCleanup) { /* Committed stop is authoritative; cleanup can be retried. */ }
                    return repository.result(actor,command.id());
                }
                // A lost control lease stops renewal/new execution. Query may still
                // observe and persist an actual system stop without using that lease.
                if(!live.get())renewal.cancel(false);
                TimeUnit.MILLISECONDS.sleep(500);
                try {receipt=runner.queryUse(command);}catch(RuntimeException pending) {receipt=null;}
            }
        } catch(InterruptedException interrupted) {Thread.currentThread().interrupt();}
        finally {renewal.cancel(false);}
        try {repository.uncertain(command,"ENVIRONMENT_USE_STOP_UNCONFIRMED");}
        catch(RuntimeException noAuthority) { /* Retain original use and sandbox capacity. */ }
        throw new WorkspaceFailure(409,"ENVIRONMENT_USE_RECONCILIATION_REQUIRED");
    }
    public void cancel(Actor actor,UUID useId) {repository.cancel(actor,useId);}
    /** Existing facts/GET only; does not reacquire an expired execution lease. */
    public Receipt reconcile(Actor actor,UUID workspace,UUID useId) {
        var command=repository.lookup(actor,useId);
        if(!command.workspaceId().equals(workspace))throw new WorkspaceFailure(404,"RESOURCE_NOT_FOUND");
        var receipt=repository.receipt(command);
        if(receipt.resultHash()==null) {
            try {receipt=runner.queryUse(command);}
            catch(RuntimeException unavailable) {throw new WorkspaceFailure(409,"ENVIRONMENT_USE_RECONCILIATION_REQUIRED");}
        }
        var durable=repository.receipt(command);
        if(receipt==null||receipt.resultHash()==null||durable.resultHash()==null)
            throw new WorkspaceFailure(409,"ENVIRONMENT_USE_RECONCILIATION_REQUIRED");
        if(!receipt.resultHash().equals(durable.resultHash()))throw new WorkspaceFailure(503,"RUNNER_RECEIPT_MISMATCH");
        if(!java.util.Set.of("COMMITTED","FAILED","STOPPED").contains(durable.state()))repository.complete(command);
        repository.lookup(actor,useId); // Recheck live authorization before projecting metadata.
        try {runner.queryUse(command);}catch(RuntimeException cleanupPending) { /* Durable stop is authoritative. */ }
        return repository.receipt(command);
    }
}
