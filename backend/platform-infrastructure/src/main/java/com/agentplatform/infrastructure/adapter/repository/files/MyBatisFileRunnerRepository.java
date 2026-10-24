package com.agentplatform.infrastructure.adapter.repository.files;

import com.agentplatform.domain.files.*;
import com.agentplatform.domain.files.FileData.*;
import com.agentplatform.domain.workspace.RunnerInstance;
import com.agentplatform.infrastructure.dao.files.*;
import com.agentplatform.infrastructure.dao.po.FileRunnerRows.Physical;
import com.agentplatform.infrastructure.dao.workspace.*;
import com.agentplatform.infrastructure.workspace.RunnerSignatures;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Narrow runner facts; this role never mutates lifecycle ownership or financial accounting. */
public final class MyBatisFileRunnerRepository implements FileRunnerRepository {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final FileRunnerMapper runner;
    private final FileMapper files;
    private final WorkspaceMapper workspaces;
    private final EnvironmentPreparationMapper environments;
    private final EnvironmentExecutionMapper instances;
    private final FileTransferMapper transfers;
    public MyBatisFileRunnerRepository(FileRunnerMapper runner,FileMapper files,WorkspaceMapper workspaces,
            EnvironmentPreparationMapper environments,EnvironmentExecutionMapper instances,FileTransferMapper transfers) {
        this.runner=runner;this.files=files;this.workspaces=workspaces;this.environments=environments;this.instances=instances;this.transfers=transfers;
    }
    private static void tx() {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("File runner transaction required");
    }
    private static FileFailure stale(){return new FileFailure(409,"FILE_STALE_OWNER");}
    private static void one(int count){if(count!=1)throw stale();}
    private static void beforeCommit(Runnable action){
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void beforeCommit(boolean readOnly){action.run();}
        });
    }
    private void lock(Command command) {
        tx();var operation=command.operation();environments.lockSlot();
        if(workspaces.lockWorkspace(operation.spaceId(),operation.workspaceId())==null)throw stale();
        files.lockLease(operation.spaceId(),operation.workspaceId());files.lockOperation(operation.id());
        if(!command.equals(lookup(operation.spaceId(),operation.workspaceId(),operation.id())))throw stale();
    }
    @Override public void register(RunnerInstance instance) {
        tx();instances.registerRunner(instance);
        if(!instance.equals(instances.runner(instance.id()))||!instances.runnerActive(instance.id()))throw stale();
    }
    @Override public Command lookup(UUID space,UUID workspace,UUID id) {
        tx();var operation=files.operation(id);
        if(operation==null||!operation.spaceId().equals(space)||!operation.workspaceId().equals(workspace))throw new FileFailure(404,"RESOURCE_NOT_FOUND");
        var row=workspaces.workspace(space,workspace);
        if(row==null)throw new FileFailure(404,"RESOURCE_NOT_FOUND");
        var upload=operation.uploadId()==null?null:files.upload(operation.uploadId());
        if(upload!=null&&(!upload.spaceId().equals(space)||!upload.workspaceId().equals(workspace)))throw stale();
        var base=upload==null||upload.baseVersionId()==null?null:files.version(space,upload.fileId(),upload.baseVersionId());
        return new Command(Execution.freeze(operation),upload==null?null:Input.freeze(upload),row.projectId(),base==null?null:base.sha256());
    }
    private void active(Command command,UUID instance,boolean accepted) {
        var operation=command.operation();
        if(!instances.runnerActive(instance)||!files.current(command)
                ||!workspaces.authorized(new com.agentplatform.domain.workspace.WorkspaceData.Actor(operation.actorId(),operation.spaceId())))throw stale();
        if(accepted) {
            var row=runner.command(operation.commandId());
            if(row==null||!row.runnerInstanceId().equals(instance))throw stale();
        }
    }
    @Override public boolean accept(Command command,RunnerInstance instance,String requestHash) {
        lock(command);active(command,instance.id(),false);
        var previous=runner.command(command.operation().commandId());
        if(previous!=null) {
            if(!previous.requestHash().equals(requestHash)||!previous.imageId().equals(command.operation().helperImageId())
                    ||!previous.policyHash().equals(command.operation().helperPolicyHash()))throw new FileFailure(409,"FILE_COMMAND_CONFLICT");
            return false;
        }
        one(runner.accept(command,instance.id(),requestHash));beforeCommit(()->active(command,instance.id(),true));return true;
    }
    @Override public void guard(Command command,UUID instance) {lock(command);active(command,instance,true);}
    @Override public FileRuntime.Physical physical(Command command) {
        tx();var row=runner.command(command.operation().commandId());
        return row==null?null:new FileRuntime.Physical(row.containerId(),row.startedAt(),row.execId());
    }
    @Override public void created(Command command,UUID instance,String container) {
        guard(command,instance);one(runner.created(command,instance,container));beforeCommit(()->active(command,instance,true));
    }
    @Override public void startRequested(Command command,UUID instance) {
        guard(command,instance);one(runner.requestStart(command,instance));beforeCommit(()->active(command,instance,true));
    }
    @Override public void started(Command command,UUID instance,String time) {
        guard(command,instance);Instant.parse(time);one(runner.started(command,instance,time));beforeCommit(()->active(command,instance,true));
    }
    @Override public void exec(Command command,UUID instance,String exec) {
        guard(command,instance);one(runner.exec(command,instance,exec));beforeCommit(()->active(command,instance,true));
    }
    @Override public boolean priorRunnerStopped(Command command,UUID observer) {
        tx();return runner.priorRunnerProof(command,observer);
    }
    @Override public boolean mayObserve(Command command,UUID observer) {
        tx();var row=runner.command(command.operation().commandId());
        return row!=null&&instances.runnerActive(observer)&&(row.runnerInstanceId().equals(observer)||runner.priorRunnerProof(command,observer));
    }
    @Override public Receipt receipt(Command command) {tx();return files.receipt(command);}
    private Physical observation(Command command,UUID observer) {
        lock(command);var row=runner.command(command.operation().commandId());
        if(row==null||!instances.runnerActive(observer)||(!row.runnerInstanceId().equals(observer)&&!runner.priorRunnerProof(command,observer)))throw stale();
        return row;
    }
    private void validateStop(Command command,Physical physical,FileRuntime.Stop stop) {
        if(stop==null||!command.operation().commandId().equals(stop.commandId())||!physical.imageId().equals(stop.imageId())
                ||!Objects.equals(physical.containerId(),stop.containerId())||!Objects.equals(physical.startedAt(),stop.startedAt())
                ||!Objects.equals(physical.execId(),stop.execId())||!stop.execStopped()||!stop.callbacksStopped()
                ||stop.observedAt()==null||stop.observedAt().isAfter(files.databaseTime()))throw stale();
        if(stop.neverStarted()) {
            if(stop.startedAt()!=null||stop.execId()!=null||stop.finishedAt()!=null||physical.startRequested())throw stale();
        } else if(stop.containerId()==null||stop.startedAt()==null||stop.finishedAt()==null
                ||!Instant.parse(stop.startedAt()).isAfter(Instant.EPOCH)
                ||!Instant.parse(stop.finishedAt()).isAfter(Instant.parse(stop.startedAt()))
                ||Instant.parse(stop.finishedAt()).isAfter(stop.observedAt()))throw stale();
    }
    @Override public void recordResult(Command command,UUID observer,FileRuntime.Result result) {
        var physical=observation(command,observer);
        boolean original=physical.runnerInstanceId().equals(observer);
        if(!original&&!"UNKNOWN".equals(result.state()))throw stale();
        if(!"UNKNOWN".equals(result.state()))active(command,observer,true);
        var stop=JSON.readValue(result.stopText(),FileRuntime.Stop.class);validateStop(command,physical,stop);
        if(java.util.Set.of("APPLIED","SCANNED").contains(result.state())&&(stop.neverStarted()||stop.execId()==null))throw stale();
        if("APPLIED".equals(result.state())&&!"APPLY_UPLOAD".equals(command.operation().kind())
                ||"SCANNED".equals(result.state())&&!"SCAN".equals(command.operation().kind()))throw stale();
        var previous=files.receipt(command);
        String resultHash=RunnerSignatures.sha256(result.resultText().getBytes(StandardCharsets.UTF_8));
        String stopHash=RunnerSignatures.sha256(result.stopText().getBytes(StandardCharsets.UTF_8));
        if(previous!=null) {
            if(!previous.resultState().equals(result.state())||!previous.resultHash().equals(resultHash)||!previous.stopHash().equals(stopHash))
                throw new FileFailure(409,"FILE_RECEIPT_CONFLICT");
            return;
        }
        runner.receipt(command,new Receipt(command.operation().commandId(),result.state(),result.resultText(),resultHash,
                result.stopText(),stopHash,observer,original?null:physical.runnerInstanceId()));
        if(command.upload()!=null) {
            // The stop receipt includes exact helper/exec and callback quiescence. Closing an S3 stream alone is insufficient.
            String keyHash=RunnerSignatures.sha256(command.upload().objectKey().getBytes(StandardCharsets.UTF_8));
            var owner=transfers.runnerOwner(physical.runnerInstanceId(),keyHash);
            if(owner!=null)one(transfers.releaseRunner(owner,physical.runnerInstanceId(),keyHash,command.operation().commandId()));
        }
        beforeCommit(()->{observation(command,observer);if(!"UNKNOWN".equals(result.state()))active(command,observer,true);});
    }
}
