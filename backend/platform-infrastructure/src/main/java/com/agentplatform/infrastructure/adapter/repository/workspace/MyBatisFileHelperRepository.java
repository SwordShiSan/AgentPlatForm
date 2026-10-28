package com.agentplatform.infrastructure.adapter.repository.workspace;

import com.agentplatform.domain.workspace.*;
import com.agentplatform.domain.workspace.EnvironmentUseData.Command;
import com.agentplatform.domain.workspace.FileHelperData.*;
import com.agentplatform.domain.workspace.WorkspaceData.Actor;
import com.agentplatform.infrastructure.dao.po.EnvironmentRows.Environment;
import com.agentplatform.infrastructure.dao.po.EnvironmentUseRows.Use;
import com.agentplatform.infrastructure.dao.po.FileHelperRows.Helper;
import com.agentplatform.infrastructure.dao.workspace.*;
import com.agentplatform.infrastructure.workspace.RunnerSignatures;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.time.temporal.ChronoUnit;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Durable helper identity and stop facts. Every mutation locks slot -> workspace -> environment -> use -> helper. */
public final class MyBatisFileHelperRepository implements FileHelperRepository {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final FileHelperMapper helpers;
    private final EnvironmentUseMapper uses;
    private final EnvironmentPreparationMapper environments;
    private final EnvironmentExecutionMapper execution;
    private final WorkspaceMapper workspaces;

    public MyBatisFileHelperRepository(FileHelperMapper helpers,EnvironmentUseMapper uses,EnvironmentPreparationMapper environments,
            EnvironmentExecutionMapper execution,WorkspaceMapper workspaces) {
        this.helpers=helpers;this.uses=uses;this.environments=environments;this.execution=execution;this.workspaces=workspaces;
    }

    private static void tx() {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("File helper transaction required");
    }
    private static WorkspaceFailure stale() {return new WorkspaceFailure(409,"ENVIRONMENT_USE_STALE_OWNER");}
    private static String hash(String text) {return RunnerSignatures.sha256(text.getBytes(StandardCharsets.UTF_8));}

    private Command command(Use row) {
        return new Command(row.id(),row.spaceId(),row.workspaceId(),row.environmentId(),row.actorId(),row.ownerId(),row.generation(),row.fence(),
                row.creationCommandId(),row.creationFence(),row.projectId(),row.imageId(),row.policyHash(),row.containerId(),row.containerStartedAt(),
                row.kind(),row.requestHash(),JSON.readValue(row.requestText(),EnvironmentUseData.Request.class),row.executeUntil(),row.deadlineAt());
    }

    private Use lock(Command command,UUID observer,boolean stopObservation) {
        tx();
        workspaces.authorized(new Actor(command.actorId(),command.spaceId()));
        var slot=environments.lockSlot();
        if(workspaces.lockWorkspace(command.spaceId(),command.workspaceId())==null)throw new WorkspaceFailure(404,"RESOURCE_NOT_FOUND");
        Environment environment=environments.lockEnvironment(command.spaceId(),command.workspaceId());
        Use row=uses.lockUse(command.id());
        if(environment==null||row==null||!command.equals(command(row))||!environment.id().equals(command.environmentId())
                ||environment.generation()!=command.generation()||environment.fence()!=command.fence()
                ||!command.environmentId().equals(slot.environmentId())||!Objects.equals(slot.generation(),command.generation()))throw stale();
        if(stopObservation) {
            if(!uses.runnerMayReconcile(command.id(),observer))throw new WorkspaceFailure(409,"RUNNER_INSTANCE_STOP_UNPROVEN");
        } else if(!observer.equals(row.runnerInstanceId())||!execution.runnerActive(observer)||!uses.owned(command,true,true)
                ||!workspaces.authorized(new Actor(command.actorId(),command.spaceId())))throw stale();
        return row;
    }

    private Helper lockedHelper(Command command) {
        Helper row=helpers.lock(command.id());
        if(row==null)throw new WorkspaceFailure(409,"FILE_HELPER_INTENT_MISSING");
        if(!row.spaceId().equals(command.spaceId())||!row.workspaceId().equals(command.workspaceId())
                ||!row.environmentId().equals(command.environmentId())||!row.ownerId().equals(command.ownerId())
                ||row.generation()!=command.generation()||row.fence()!=command.fence())throw stale();
        return row;
    }
    private void open(Helper row) {
        if(helpers.stop(row.useId())!=null)throw new WorkspaceFailure(409,"FILE_HELPER_STOPPED");
    }

    private Snapshot snapshot(Helper row) {
        String expected=hash(row.useId()+"\n"+row.spaceId()+"\n"+row.workspaceId()+"\n"+row.environmentId()+"\n"
                +row.ownerId()+"\n"+row.generation()+"\n"+row.fence()+"\n"+row.runnerInstanceId()+"\n"+row.imageId()+"\n"+row.policyHash()+"\n"+row.containerName());
        if(!expected.equals(row.intentHash()))throw new WorkspaceFailure(503,"FILE_HELPER_INTENT_CORRUPT");
        var intent=new Intent(row.useId(),row.imageId(),row.policyHash(),row.containerName());
        Physical physical=row.containerId()==null?null:new Physical(row.containerId(),row.imageId(),row.startedAt(),row.execId());
        return new Snapshot(intent,row.runnerInstanceId(),physical,row.startRequested(),retirement(row.useId()));
    }

    @Override public void intent(Command command,UUID runner,Intent intent) {
        lock(command,runner,false);
        if(intent==null||!command.id().equals(intent.useId())||!command.policyHash().equals(intent.policyHash()))throw stale();
        String digest=hash(command.id()+"\n"+command.spaceId()+"\n"+command.workspaceId()+"\n"+command.environmentId()+"\n"
                +command.ownerId()+"\n"+command.generation()+"\n"+command.fence()+"\n"+runner+"\n"+intent.imageId()+"\n"+intent.policyHash()+"\n"+intent.containerName());
        Helper previous=helpers.lock(command.id());
        if(previous!=null) {
            if(!previous.runnerInstanceId().equals(runner)||!previous.imageId().equals(intent.imageId())
                    ||!previous.policyHash().equals(intent.policyHash())||!previous.containerName().equals(intent.containerName())
                    ||!previous.intentHash().equals(digest))throw new WorkspaceFailure(409,"FILE_HELPER_INTENT_CONFLICT");
            return;
        }
        helpers.insert(command,runner,intent,digest);
        if(helpers.lock(command.id())==null)throw stale();
        beforeCommitOwner(command,runner);
    }

    @Override public Snapshot find(Command command,UUID observer) {
        Use use=lock(command,observer,true);
        Helper helper=helpers.find(use.id());
        return helper==null?null:snapshot(helper);
    }

    @Override public void created(Command command,UUID runner,Physical physical) {
        lock(command,runner,false);Helper row=lockedHelper(command);
        open(row);
        if(physical==null||physical.startedAt()!=null||physical.execId()!=null||!row.imageId().equals(physical.imageId()))throw stale();
        if(row.containerId()!=null) {
            if(!row.containerId().equals(physical.containerId()))throw new WorkspaceFailure(409,"FILE_HELPER_IDENTITY_CONFLICT");
            return;
        }
        if(row.startRequested()||helpers.created(command.id(),physical.containerId())!=1)throw stale();
        beforeCommitOwner(command,runner);
    }

    @Override public void requestStart(Command command,UUID runner) {
        lock(command,runner,false);Helper row=lockedHelper(command);
        open(row);
        if(row.startRequested())return;
        if(row.containerId()==null||helpers.startRequested(command.id())!=1)throw stale();
        beforeCommitOwner(command,runner);
    }

    @Override public void started(Command command,UUID runner,Physical physical) {
        lock(command,runner,false);Helper row=lockedHelper(command);
        open(row);
        if(physical==null||physical.startedAt()==null||physical.execId()!=null||!Objects.equals(row.containerId(),physical.containerId())
                ||!row.imageId().equals(physical.imageId())||!row.startRequested())throw stale();
        if(row.startedAt()!=null) {
            if(!row.startedAt().equals(physical.startedAt()))throw new WorkspaceFailure(409,"FILE_HELPER_IDENTITY_CONFLICT");
            return;
        }
        if(helpers.started(command.id(),physical)!=1)throw stale();
        beforeCommitOwner(command,runner);
    }

    @Override public void recordExec(Command command,UUID runner,Physical physical) {
        lock(command,runner,false);Helper row=lockedHelper(command);
        open(row);
        if(physical==null||physical.execId()==null||!Objects.equals(row.containerId(),physical.containerId())
                ||!Objects.equals(row.startedAt(),physical.startedAt())||!row.imageId().equals(physical.imageId()))throw stale();
        if(row.execId()!=null) {
            if(!row.execId().equals(physical.execId()))throw new WorkspaceFailure(409,"FILE_HELPER_IDENTITY_CONFLICT");
            return;
        }
        if(helpers.recordExec(command.id(),physical)!=1||uses.trustedFileExec(command,runner,physical.execId())!=1)throw stale();
        beforeCommitOwner(command,runner);
    }

    @Override public void stopped(Command command,UUID observer,Stopped stop) {
        Use use=lock(command,observer,true);Helper row=lockedHelper(command);
        if(stop==null||!row.imageId().equals(stop.imageId()))throw stale();
        stop=new Stopped(stop.physical(),stop.imageId(),stop.neverStarted(),stop.finishedAt(),stop.execStopped(),stop.callbacksStopped(),
                stop.observedAt().truncatedTo(ChronoUnit.MICROS));
        Physical physical=stop.physical();
        if(physical==null) {
            if(!stop.neverStarted()||row.containerId()!=null||row.startRequested())throw new WorkspaceFailure(409,"FILE_HELPER_STOP_MISMATCH");
        } else if(!Objects.equals(row.containerId(),physical.containerId())||!row.imageId().equals(physical.imageId())
                ||!Objects.equals(row.startedAt(),physical.startedAt())||!Objects.equals(row.execId(),physical.execId())
                ||stop.neverStarted()&&row.startRequested()||!stop.neverStarted()&&!row.startRequested()) {
            throw new WorkspaceFailure(409,"FILE_HELPER_STOP_MISMATCH");
        }
        var previous=helpers.stop(command.id());
        String text=JSON.writeValueAsString(stop),digest=hash(text);
        if(previous!=null) {
            if(!previous.contentHash().equals(digest)||!previous.stopText().equals(text))throw new WorkspaceFailure(409,"FILE_HELPER_STOP_CONFLICT");
            return;
        }
        if(!helpers.fresh(stop))throw new WorkspaceFailure(409,"FILE_HELPER_STOP_EXPIRED");
        UUID prior=observer.equals(row.runnerInstanceId())?null:row.runnerInstanceId();
        helpers.insertStop(command.id(),observer,prior,stop,physical==null?null:physical.containerId(),
                physical==null?null:physical.startedAt(),physical==null?null:physical.execId(),text,digest);
        beforeCommitStop(command,observer);
        if(use.runnerInstanceId()==null)throw stale();
    }

    @Override public Stopped retirement(Command command) {
        tx();
        var use=uses.use(command.id());
        if(use==null||!command.equals(command(use)))throw stale();
        return retirement(command.id());
    }

    private Stopped retirement(UUID id) {
        var row=helpers.stop(id);
        if(row==null)return null;
        if(!row.contentHash().equals(hash(row.stopText())))throw new WorkspaceFailure(503,"FILE_HELPER_STOP_CORRUPT");
        Stopped stop=JSON.readValue(row.stopText(),Stopped.class);
        Physical physical=stop.physical();
        if(!stop.observedAt().equals(row.observedAt())||!Objects.equals(row.containerId(),physical==null?null:physical.containerId())
                ||!Objects.equals(row.imageId(),stop.imageId())||!Objects.equals(row.startedAt(),physical==null?null:physical.startedAt())
                ||!Objects.equals(row.execId(),physical==null?null:physical.execId())||row.neverStarted()!=stop.neverStarted()
                ||!Objects.equals(row.finishedAt(),stop.finishedAt())||row.execStopped()!=stop.execStopped()
                ||row.callbacksStopped()!=stop.callbacksStopped())throw new WorkspaceFailure(503,"FILE_HELPER_STOP_CORRUPT");
        return stop;
    }

    private void beforeCommitOwner(Command command,UUID runner) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void beforeCommit(boolean readOnly) {lock(command,runner,false);}
        });
    }
    private void beforeCommitStop(Command command,UUID observer) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void beforeCommit(boolean readOnly) {lock(command,observer,true);}
        });
    }
}
