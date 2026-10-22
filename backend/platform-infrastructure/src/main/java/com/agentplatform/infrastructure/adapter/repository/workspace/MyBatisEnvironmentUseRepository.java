package com.agentplatform.infrastructure.adapter.repository.workspace;

import com.agentplatform.domain.workspace.*;
import com.agentplatform.domain.workspace.EnvironmentUseData.*;
import com.agentplatform.domain.workspace.WorkspaceData.Actor;
import com.agentplatform.infrastructure.dao.po.EnvironmentRows.Environment;
import com.agentplatform.infrastructure.dao.po.EnvironmentUseRows.*;
import com.agentplatform.infrastructure.dao.workspace.*;
import com.agentplatform.infrastructure.workspace.RunnerSignatures;
import com.agentplatform.types.Uuid7;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Private-use facts. All locks follow slot -> workspace -> environment -> use/result. No I/O in transactions. */
public final class MyBatisEnvironmentUseRepository implements EnvironmentUseRepository {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final EnvironmentUseMapper uses;
    private final EnvironmentPreparationMapper environments;
    private final EnvironmentExecutionMapper execution;
    private final WorkspaceMapper workspaces;
    public MyBatisEnvironmentUseRepository(EnvironmentUseMapper uses,EnvironmentPreparationMapper environments,
            EnvironmentExecutionMapper execution,WorkspaceMapper workspaces) {
        this.uses=uses;this.environments=environments;this.execution=execution;this.workspaces=workspaces;
    }
    private static void tx() {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Environment use transaction required");
    }
    private static WorkspaceFailure stale() {return new WorkspaceFailure(409,"ENVIRONMENT_USE_STALE_OWNER");}
    private static String hash(String text) {return RunnerSignatures.sha256(text.getBytes(StandardCharsets.UTF_8));}
    private Environment lock(UUID space,UUID workspace) {
        tx();environments.lockSlot();
        if(workspaces.lockWorkspace(space,workspace)==null)throw new WorkspaceFailure(404,"RESOURCE_NOT_FOUND");
        var environment=environments.lockEnvironment(space,workspace);
        if(environment==null)throw new WorkspaceFailure(404,"RESOURCE_NOT_FOUND");
        return environment;
    }
    private void authorized(Actor actor) {
        if(!workspaces.authorized(actor))throw new WorkspaceFailure(403,"ENVIRONMENT_AUTHORIZATION_REVOKED");
    }
    private void event(Environment row) {
        var payload=new LinkedHashMap<String,Object>();payload.put("workspaceId",row.workspaceId().toString());payload.put("state",row.state());
        payload.put("generation",row.generation());payload.put("version",row.version());payload.put("operationId",row.operationId().toString());payload.put("errorCode",row.errorCode());
        environments.insertEvent(Uuid7.next(),row,JSON.writeValueAsString(payload));
    }
    private Command command(Use row) {
        return new Command(row.id(),row.spaceId(),row.workspaceId(),row.environmentId(),row.actorId(),row.ownerId(),row.generation(),row.fence(),
                row.creationCommandId(),row.creationFence(),row.projectId(),row.imageId(),row.policyHash(),row.containerId(),row.containerStartedAt(),
                row.kind(),row.requestHash(),JSON.readValue(row.requestText(),Request.class),row.executeUntil(),row.deadlineAt());
    }
    private static void validateRequest(Request request,String text) {
        if(request==null||text.getBytes(StandardCharsets.UTF_8).length>65536)throw new WorkspaceFailure(400,"INVALID_WORKSPACE_USE");
        if(request.file()!=null) {
            var file=request.file();String path=file.path();
            boolean rootList=path.isEmpty()&&file.action()==SandboxRuntime.FileAction.LIST;
            if(!rootList&&(path.isEmpty()||path.length()>2048||path.startsWith("/")||path.indexOf('\\')>=0||path.indexOf('\0')>=0
                    ||Arrays.stream(path.split("/",-1)).anyMatch(p->p.isEmpty()||p.equals(".")||p.equals(".."))))
                throw new WorkspaceFailure(400,"INVALID_WORKSPACE_PATH");
            if(file.action()==SandboxRuntime.FileAction.WRITE) {
                try {if(file.contentBase64()==null||Base64.getDecoder().decode(file.contentBase64()).length>32768)throw new IllegalArgumentException();}
                catch(IllegalArgumentException invalid) {throw new WorkspaceFailure(400,"INVALID_WORKSPACE_CONTENT");}
            } else if(file.contentBase64()!=null)throw new WorkspaceFailure(400,"INVALID_WORKSPACE_CONTENT");
        }
    }
    @Override public Admission admit(Actor actor,UUID workspace,UUID requestId,UUID owner,Request request) {
        Objects.requireNonNull(requestId);Objects.requireNonNull(owner);
        String text=JSON.writeValueAsString(request);validateRequest(request,text);
        var environment=lock(actor.spaceId(),workspace);authorized(actor);
        String digest=hash(workspace+"\n"+text);
        var previous=uses.request(actor,requestId);
        if(previous!=null) {
            if(!previous.workspaceId().equals(workspace)||!previous.requestHash().equals(digest))throw new WorkspaceFailure(409,"IDEMPOTENCY_CONFLICT");
            // An idempotent read never transfers execution ownership to the new caller.
            return new Admission(command(previous),false);
        }
        var slot=environments.lockSlot();var operation=environments.operation(environment.operationId());
        if(!"READY".equals(environment.state())||!"COMMITTED".equals(operation.status())||!"PREPARE".equals(operation.kind())
                ||!environment.id().equals(slot.environmentId())||!Objects.equals(slot.generation(),environment.generation()))
            throw new WorkspaceFailure(409,"ENVIRONMENT_NOT_READY");
        if(environments.unresolvedUse(environment.id()))throw new WorkspaceFailure(409,"ENVIRONMENT_USE_BUSY");
        var physical=uses.container(environment.id());var workspaceRow=workspaces.lockWorkspace(actor.spaceId(),workspace);
        if(physical==null||physical.containerId()==null||physical.startedAt()==null||physical.readinessHash()==null)throw stale();
        if(uses.advanceFence(actor.spaceId(),workspace,environment.id(),environment.fence(),environment.version())!=1)throw stale();
        var row=new Use(Uuid7.next(),actor.spaceId(),workspace,environment.id(),actor.userId(),owner,requestId,environment.generation(),
                Math.addExact(environment.fence(),1),environment.creationCommandId(),environment.creationFence(),workspaceRow.projectId(),
                environment.imageId(),environment.policyHash(),physical.containerId(),physical.startedAt(),request.kind(),digest,text,
                null,"CLAIMED","PENDING",null,false,null,null,null,null,null,1);
        uses.insertUse(row);event(environments.environment(actor.spaceId(),workspace));
        return new Admission(command(uses.use(row.id())),true);
    }
    @Override public Command lookup(UUID id) {
        tx();var row=uses.use(id);if(row==null)throw new WorkspaceFailure(404,"RESOURCE_NOT_FOUND");return command(row);
    }
    private Use identity(Command cmd,boolean slotRequired) {
        var environment=lock(cmd.spaceId(),cmd.workspaceId());var row=uses.lockUse(cmd.id());
        if(row==null||!command(row).equals(cmd)||!environment.id().equals(cmd.environmentId())
                ||environment.generation()!=cmd.generation()||environment.fence()!=cmd.fence())throw stale();
        if(slotRequired) {
            var slot=environments.lockSlot();
            if(!environment.id().equals(slot.environmentId())||!Objects.equals(slot.generation(),cmd.generation()))throw stale();
        }
        return row;
    }
    private Use guard(Command cmd,boolean executing,boolean active) {
        var row=identity(cmd,true);
        if(!uses.owned(cmd,executing,active))throw stale();
        authorized(new Actor(cmd.actorId(),cmd.spaceId()));return row;
    }
    @Override public void checkOwnership(Command cmd) {guard(cmd,false,false);}
    @Override public void check(Command cmd,UUID runner) {
        var row=guard(cmd,true,true);
        if(!runner.equals(row.runnerInstanceId())||!execution.runnerActive(runner))throw stale();
    }
    @Override public void checkStopObservation(Command cmd,UUID runner) {
        var row=identity(cmd,true);
        if(row.runnerInstanceId()==null&&row.execId()==null&&execution.runnerActive(runner))uses.bindStopObserver(cmd.id(),runner);
        if(!uses.runnerMayReconcile(cmd.id(),runner))throw new WorkspaceFailure(409,"RUNNER_INSTANCE_STOP_UNPROVEN");
    }
    @Override public void checkCompletion(Command cmd) {
        tx();lock(cmd.spaceId(),cmd.workspaceId());
        if(uses.stoppedCompletion(cmd))return;
        var row=identity(cmd,true);
        if(!Set.of("COMMITTED","FAILED").contains(row.status()))throw stale();
        readFact(cmd); // Already committed facts confer convergence, never new execution rights.
    }
    @Override public void renew(Command cmd) {guard(cmd,false,true);if(uses.renew(cmd)!=1)throw stale();}
    @Override public boolean accept(Command cmd,UUID runner) {
        var row=guard(cmd,true,true);
        if(!execution.runnerActive(runner))throw new WorkspaceFailure(409,"RUNNER_INSTANCE_UNREGISTERED");
        if(row.runnerInstanceId()!=null) {
            if(!runner.equals(row.runnerInstanceId()))throw new WorkspaceFailure(409,"RUNNER_INSTANCE_STOP_UNPROVEN");
            return false;
        }
        if(uses.accept(cmd,runner)!=1)throw stale();return true;
    }
    @Override public boolean cancelled(Command cmd) {
        var row=guard(cmd,false,true);return row.cancelRequested();
    }
    @Override public void cancel(Actor actor,UUID id) {
        var cmd=lookup(id);lock(cmd.spaceId(),cmd.workspaceId());authorized(actor);
        if(!actor.spaceId().equals(cmd.spaceId())||!actor.userId().equals(cmd.actorId()))throw new WorkspaceFailure(404,"RESOURCE_NOT_FOUND");
        uses.lockUse(id);uses.cancel(actor,id);
    }
    @Override public boolean stopCommitted(UUID id) {
        tx();var row=uses.use(id);return row!=null&&row.stopOperationId()!=null&&environments.confirmedStop(row.stopOperationId());
    }
    @Override public void recordExec(Command cmd,UUID runner,String container,String exec) {
        check(cmd,runner);
        if(!cmd.containerId().equals(container)||exec==null||!exec.matches("[0-9a-f]{64}")||uses.recordExec(cmd,runner,exec)!=1)throw stale();
    }
    private void validate(Command cmd,UUID runner,Fact fact,Use row) {
        if(fact==null||!cmd.id().equals(fact.useId())||!cmd.environmentId().equals(fact.environmentId())||cmd.generation()!=fact.generation()
                ||cmd.fence()!=fact.fence()||!cmd.requestHash().equals(fact.requestHash())||!runner.equals(fact.runnerInstanceId())
                ||!Set.of("KNOWN","UNKNOWN").contains(fact.resultState())||fact.errorCode()!=null&&!fact.errorCode().matches("[A-Z_]{1,64}"))
            throw new WorkspaceFailure(409,"ENVIRONMENT_USE_RESULT_MISMATCH");
        var output=fact.output();
        if("KNOWN".equals(fact.resultState())) {
            if(output==null||fact.stop()!=null||output.timedOut()||output.cancelled()||!Objects.equals(row.execId(),output.execId())||output.execId()==null
                    ||output.stdout()==null||output.stderr()==null||output.stdout().getBytes(StandardCharsets.UTF_8).length+(long)output.stderr().getBytes(StandardCharsets.UTF_8).length>65536)
                throw new WorkspaceFailure(409,"ENVIRONMENT_USE_OUTPUT_MISMATCH");
        } else if(output!=null||fact.stop()==null)throw new WorkspaceFailure(409,"ENVIRONMENT_USE_UNKNOWN_OUTPUT");
        if(fact.stop()!=null) {
            var stop=fact.stop();
            if(!cmd.containerId().equals(stop.containerId())||!cmd.imageId().equals(stop.image())||!cmd.containerStartedAt().equals(stop.startedAt())
                    ||!stop.execStopped()||!stop.callbacksStopped()||stop.observedAt()==null||stop.finishedAt()==null||stop.finishedAt().startsWith("0001")
                    ||!uses.validStop(stop))throw new WorkspaceFailure(409,"RUNNER_STOP_MISMATCH");
        }
        if(output!=null&&!Objects.equals(output.stopReceipt(),fact.stop()))throw new WorkspaceFailure(409,"RUNNER_STOP_MISMATCH");
    }
    @Override public void commitFact(Command cmd,UUID runner,Fact fact) {
        var row=identity(cmd,true);validate(cmd,runner,fact,row);
        boolean system=fact.stop()!=null&&"UNKNOWN".equals(fact.resultState());
        if(system)checkStopObservation(cmd,runner);else check(cmd,runner);
        String text=JSON.writeValueAsString(fact),digest=hash(text);
        if(text.getBytes(StandardCharsets.UTF_8).length>524288)throw new WorkspaceFailure(409,"ENVIRONMENT_USE_OUTPUT_MISMATCH");
        var previous=uses.result(cmd.id());
        if(previous!=null) {
            if(!previous.contentHash().equals(digest))throw new WorkspaceFailure(409,"ENVIRONMENT_USE_RESULT_CONFLICT");return;
        }
        UUID prior=runner.equals(row.runnerInstanceId())?null:row.runnerInstanceId();
        uses.insertResult(cmd.id(),new Result(text,digest,fact.output()==null?-1:fact.output().exitCode(),fact.output()!=null&&fact.output().truncated(),runner,prior,
                system?"SYSTEM_STOP":"EXECUTION_OWNER",system?runner:cmd.ownerId(),null));
    }
    private Fact readFact(Command cmd) {
        var row=uses.result(cmd.id());if(row==null)throw new WorkspaceFailure(409,"ENVIRONMENT_USE_RESULT_PENDING");
        if(!row.contentHash().equals(hash(row.resultText())))throw new WorkspaceFailure(503,"ENVIRONMENT_USE_RESULT_CORRUPT");
        return JSON.readValue(row.resultText(),Fact.class);
    }
    @Override public Receipt receipt(Command cmd) {
        tx();var row=uses.use(cmd.id());
        if(row==null||!command(row).equals(cmd))throw stale();var fact=uses.result(cmd.id());
        String state="CLAIMED".equals(row.status())&&row.execId()!=null?"RUNNING":row.status();
        return new Receipt(cmd.id(),state,fact==null?row.resultState():readFact(cmd).resultState(),fact==null?null:fact.contentHash());
    }
    @Override public Fact committedFact(Command cmd) {
        tx();var row=uses.use(cmd.id());if(row==null||!command(row).equals(cmd))throw stale();return readFact(cmd);
    }
    @Override public Fact result(Actor actor,UUID id) {
        return readFact(lookup(actor,id));
    }
    @Override public Command lookup(Actor actor,UUID id) {
        var cmd=lookup(id);lock(cmd.spaceId(),cmd.workspaceId());authorized(actor);
        if(!actor.spaceId().equals(cmd.spaceId())||!actor.userId().equals(cmd.actorId()))throw new WorkspaceFailure(404,"RESOURCE_NOT_FOUND");
        return cmd;
    }
    @Override public void complete(Command cmd) {
        var row=uses.use(cmd.id());
        if(row!=null&&row.stopOperationId()!=null) {checkCompletion(cmd);return;}
        identity(cmd,true);var fact=readFact(cmd);
        if(fact.stop()!=null) {
            // A new system stop fact records only proven termination, never a fabricated MQ command.
            long fence=Math.addExact(cmd.fence(),1);UUID operation=Uuid7.next(),owner=Uuid7.next();
            uses.insertStopFactOperation(operation,cmd,owner,fence);
            uses.insertUseStopReceipt(Uuid7.next(),cmd,operation,fence,owner,fact,hash(JSON.writeValueAsString(fact.stop())),uses.result(cmd.id()).priorRunnerProofId());
            if(uses.projectStopped(cmd,operation,fence,fact.errorCode())!=1||uses.closeStopped(cmd,operation,fact.resultState(),fact.errorCode())!=1)throw stale();
            uses.projectWorkspaceStopped(cmd);if(uses.releaseSlot(cmd)!=1)throw stale();
        } else {
            if(Set.of("COMMITTED","FAILED").contains(row.status()))return;
            // The immutable result was checked before its own commit. This transaction only projects that fact.
            if("UNKNOWN".equals(fact.resultState()))throw new WorkspaceFailure(409,"ENVIRONMENT_STOP_UNPROVEN");
            if(uses.finish(cmd,fact.output().exitCode()==0?"COMMITTED":"FAILED","KNOWN",fact.errorCode())!=1)throw stale();
            uses.touchEnvironment(cmd);
        }
        event(environments.environment(cmd.spaceId(),cmd.workspaceId()));
    }
    @Override public void uncertain(Command cmd,String code) {
        guard(cmd,false,true);
        if(code==null||!code.matches("[A-Z_]{1,64}"))throw new IllegalArgumentException("Stable code required");
        if(uses.result(cmd.id())!=null)return;
        if(uses.uncertain(cmd,code)!=1||uses.projectBlocked(cmd,code)!=1)throw stale();
        uses.projectWorkspaceBlocked(cmd);event(environments.environment(cmd.spaceId(),cmd.workspaceId()));
        // Retain the use, container and slot; UNKNOWN is not proof of termination.
    }
}
