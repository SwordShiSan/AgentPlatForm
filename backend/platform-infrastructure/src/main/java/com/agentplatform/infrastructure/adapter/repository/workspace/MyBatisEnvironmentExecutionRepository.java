package com.agentplatform.infrastructure.adapter.repository.workspace;

import com.agentplatform.domain.workspace.*;
import com.agentplatform.domain.workspace.EnvironmentExecutionData.*;
import com.agentplatform.domain.workspace.WorkspaceData.Actor;
import com.agentplatform.infrastructure.dao.po.EnvironmentRows.Environment;
import com.agentplatform.infrastructure.dao.workspace.*;
import com.agentplatform.infrastructure.workspace.RunnerSignatures;
import com.agentplatform.types.Uuid7;
import java.util.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Single lock order: sandbox slot → workspace → environment/operation → command/receipt. */
public final class MyBatisEnvironmentExecutionRepository implements EnvironmentExecutionRepository {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final EnvironmentExecutionMapper mapper;
    private final EnvironmentPreparationMapper environments;
    private final WorkspaceMapper workspaces;
    public MyBatisEnvironmentExecutionRepository(EnvironmentExecutionMapper mapper,EnvironmentPreparationMapper environments,WorkspaceMapper workspaces) {
        this.mapper=mapper;this.environments=environments;this.workspaces=workspaces;
    }
    private static void tx() {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Environment execution transaction required");
    }
    private Environment lock(UUID space,UUID workspace) {
        if(workspaces.lockWorkspace(space,workspace)==null)throw new WorkspaceFailure(404,"RESOURCE_NOT_FOUND");
        var row=environments.lockEnvironment(space,workspace);
        if(row==null)throw new WorkspaceFailure(404,"RESOURCE_NOT_FOUND");
        return row;
    }
    private boolean authorized(Command command) {
        return "STOP".equals(command.kind())
                || "ENVIRONMENT_V3".equals(command.protocol()) && Set.of("SLEEP", "DELETE").contains(command.kind())
                || command.actorId()!=null && workspaces.authorized(new Actor(command.actorId(),command.spaceId()));
    }
    private void event(Environment row) {
        var payload=new LinkedHashMap<String,Object>();payload.put("workspaceId",row.workspaceId().toString());payload.put("state",row.state());
        payload.put("generation",row.generation());payload.put("version",row.version());payload.put("operationId",row.operationId().toString());payload.put("errorCode",row.errorCode());
        environments.insertEvent(Uuid7.next(),row,JSON.writeValueAsString(payload));
    }
    @Override public List<Publication> publications(UUID owner) {tx();return mapper.publications(owner);}
    @Override public void published(Publication item,boolean confirmed) {tx();mapper.published(item,confirmed);}
    @Override public Command claim(Publication item,UUID owner) {
        tx();var slot=environments.lockSlot();
        if(!mapper.knownPublication(item)) {quarantine(Uuid7.next(),"INVALID_ENVIRONMENT_DELIVERY",RunnerSignatures.sha256(JSON.writeValueAsBytes(item)));return null;}
        var environment=lock(item.spaceId(),item.workspaceId());
        var operation=environments.operation(item.operationId());
        if(operation==null||!environment.operationId().equals(operation.id())||!environment.id().equals(item.environmentId())
                ||environment.generation()!=item.generation()||environment.fence()!=item.fence()||!"PENDING".equals(operation.status())
                ||!environment.id().equals(slot.environmentId())||!Objects.equals(slot.generation(),item.generation()))return null;
        var pending=mapper.command(operation.id());
        if(!authorized(pending)) {
            if(mapper.rejectBeforeClaim(operation.id())!=1)throw new WorkspaceFailure(409,"ENVIRONMENT_CONCURRENT_CHANGE");
            mapper.projectRejected(environment.id());mapper.projectWorkspace(pending,"ERROR");mapper.releaseSlot(pending);
            event(environments.environment(environment.spaceId(),environment.workspaceId()));return null;
        }
        mapper.inbox(item.id(),operation.id());
        if(mapper.claim(operation.id(),owner)!=1)return null;
        return mapper.command(operation.id());
    }
    @Override public Command lookup(UUID operationId) {
        tx();environments.lockSlot();
        var command=mapper.command(operationId);
        if(command==null)throw new WorkspaceFailure(404,"RESOURCE_NOT_FOUND");
        lock(command.spaceId(),command.workspaceId());environments.operation(operationId);
        guard(command,false);return command;
    }
    @Override public Query queryLookup(UUID operationId) {
        tx();environments.lockSlot();var command=mapper.command(operationId);
        if(command==null||!"ENVIRONMENT_V3".equals(command.protocol()))throw new WorkspaceFailure(404,"RESOURCE_NOT_FOUND");
        lock(command.spaceId(),command.workspaceId());var operation=environments.operation(operationId);
        if(!authorized(command))throw new WorkspaceFailure(403,"ENVIRONMENT_AUTHORIZATION_REVOKED");
        if(Set.of("COMMITTED","FAILED").contains(operation.status()))return new Query(command,QueryMode.READ_ONLY);
        if(mapper.owned(command,true)) {guard(command,true);return new Query(command,QueryMode.EXECUTION);}
        observedGuard(command,null,null,true);return new Query(command,QueryMode.OBSERVATION);
    }
    @Override public List<Observation> observations(UUID observerId) {
        tx();Objects.requireNonNull(observerId);var slot=environments.lockSlot();
        var id=mapper.observableOperation();if(id==null)return List.of();
        var command=mapper.command(id);var row=lock(command.spaceId(),command.workspaceId());environments.operation(id);
        if(!row.operationId().equals(id)||!row.id().equals(slot.environmentId())||!Objects.equals(slot.generation(),command.generation())
                ||row.generation()!=command.generation()||row.fence()!=command.fence())return List.of();
        var lease=mapper.observe(command,observerId);
        return lease==null?List.of():List.of(new Observation(command,lease.observerId(),lease.revision()));
    }
    private void observedGuard(Command command,UUID observerId,Long revision,boolean active) {
        tx();var slot=environments.lockSlot();var row=lock(command.spaceId(),command.workspaceId());environments.operation(command.operationId());
        if(!row.operationId().equals(command.operationId())||row.generation()!=command.generation()||row.fence()!=command.fence()
                ||!command.equals(mapper.command(command.operationId()))||!mapper.observing(command,observerId,revision,active)
                ||active&&(!row.id().equals(slot.environmentId())||!Objects.equals(slot.generation(),command.generation()))
                ||mapper.commandHash(command.commandId())==null)throw new WorkspaceFailure(409,"ENVIRONMENT_OBSERVATION_STALE");
        if(!authorized(command))throw new WorkspaceFailure(403,"ENVIRONMENT_AUTHORIZATION_REVOKED");
    }
    @Override public void checkObservation(Observation observation) {
        observedGuard(observation.command(),observation.observerId(),observation.revision(),false);
    }
    @Override public void checkObservedCommand(Command command) {observedGuard(command,null,null,false);}
    @Override public void checkObservedRunner(Command command,UUID runnerId) {
        observedGuard(command,null,null,true);
        if(!mapper.runnerMayReconcile(command.commandId(),runnerId))throw new WorkspaceFailure(409,"RUNNER_INSTANCE_STOP_UNPROVEN");
    }
    @Override public Receipt observedReceipt(Command command) {observedGuard(command,null,null,true);return readReceipt(command);}
    @Override public Receipt committedReceipt(Command command) {
        tx();environments.lockSlot();lock(command.spaceId(),command.workspaceId());var operation=environments.operation(command.operationId());
        if(!command.equals(mapper.command(command.operationId()))||!Set.of("COMMITTED","FAILED").contains(operation.status())
                ||!authorized(command))throw new WorkspaceFailure(409,"ENVIRONMENT_READ_ONLY_UNAVAILABLE");
        var receipt=readReceipt(command);
        if("RUNNING".equals(receipt.state()))throw new WorkspaceFailure(503,"RUNNER_RECEIPT_CORRUPT");return receipt;
    }
    private void guard(Command command,boolean active) {
        tx();var slot=environments.lockSlot();var row=lock(command.spaceId(),command.workspaceId());environments.operation(command.operationId());
        var actual=mapper.command(command.operationId());
        if(!row.operationId().equals(command.operationId())||!command.equals(actual)||!mapper.owned(command,active)
                ||active&&(!row.id().equals(slot.environmentId())||!Objects.equals(slot.generation(),command.generation())))
            throw new WorkspaceFailure(409,"ENVIRONMENT_STALE_OWNER");
        if(!authorized(command))throw new WorkspaceFailure(403,"ENVIRONMENT_AUTHORIZATION_REVOKED");
    }
    @Override public void check(Command command) {guard(command,true);}
    @Override public void checkOwnership(Command command) {guard(command,false);}
    @Override public void renew(Command command) {guard(command,true);if(mapper.renew(command)!=1)throw new WorkspaceFailure(409,"ENVIRONMENT_STALE_OWNER");}
    @Override public void beforeCall(Command command) {guard(command,true);if(mapper.beforeCall(command)!=1)throw new WorkspaceFailure(409,"ENVIRONMENT_STALE_OWNER");}
    @Override public void registerRunner(RunnerInstance instance) {
        tx();mapper.registerRunner(instance);
        if(!instance.equals(mapper.runner(instance.id())))throw new WorkspaceFailure(409,"RUNNER_INSTANCE_CONFLICT");
        if(!mapper.runnerActive(instance.id()))throw new WorkspaceFailure(409,"RUNNER_INSTANCE_STOPPED");
    }
    @Override public void checkRunner(Command command,UUID runnerId) {
        guard(command,true);
        if(!mapper.runnerMayReconcile(command.commandId(),runnerId))throw new WorkspaceFailure(409,"RUNNER_INSTANCE_STOP_UNPROVEN");
    }
    @Override public Receipt accept(Command command,String hash,UUID runnerId) {
        guard(command,true);
        if(!mapper.runnerActive(runnerId))throw new WorkspaceFailure(409,"RUNNER_INSTANCE_UNREGISTERED");
        var previous=mapper.commandHash(command.commandId());
        if(previous!=null) {
            if(!previous.equals(hash))throw new WorkspaceFailure(409,"RUNNER_COMMAND_CONFLICT");
            return receipt(command);
        }
        if(mapper.accept(command,hash,runnerId)!=1)throw new WorkspaceFailure(409,"RUNNER_COMMAND_CONFLICT");
        return null;
    }
    @Override public Receipt receipt(Command command) {
        guard(command,false);return readReceipt(command);
    }
    private Receipt readReceipt(Command command) {
        var row=mapper.receipt(command.commandId());
        if(row==null)return new Receipt(command.commandId(),command.operationId(),command.environmentId(),command.workspaceId(),command.generation(),command.fence(),"RUNNING","MAY_HAVE_STARTED",null,null,null,0);
        if(!row.contentHash().equals(RunnerSignatures.sha256(row.resultJson().getBytes(java.nio.charset.StandardCharsets.UTF_8))))throw new WorkspaceFailure(503,"RUNNER_RECEIPT_CORRUPT");
        var result=JSON.readValue(row.resultJson(),Receipt.class);validate(command,result);
        if(!mapper.receiptPointerValid(command.commandId(),result.revision(),result.state()))throw new WorkspaceFailure(503,"RUNNER_RECEIPT_CORRUPT");
        return result;
    }
    @Override public SandboxRuntime.Stop priorStop(Command command) {
        guard(command,true);return mapper.priorStop(command);
    }
    private static void validate(Command command,Receipt receipt) {
        boolean creation=Set.of("PREPARE","WAKE").contains(command.kind());
        boolean v3Stop="ENVIRONMENT_V3".equals(command.protocol())&&Set.of("STOP","SLEEP","DELETE").contains(command.kind());
        if(receipt==null||!command.commandId().equals(receipt.commandId())||!command.operationId().equals(receipt.operationId())
                ||!command.environmentId().equals(receipt.environmentId())||!command.workspaceId().equals(receipt.workspaceId())
                ||command.generation()!=receipt.generation()||command.fence()!=receipt.fence()
                ||!(receipt.revision()==1||v3Stop&&receipt.revision()==2)
                ||receipt.state()==null||!Set.of("COMMITTED","FAILED","UNKNOWN").contains(receipt.state())
                ||receipt.errorCode()!=null&&!receipt.errorCode().matches("[A-Z_]{1,64}"))throw new WorkspaceFailure(409,"RUNNER_RECEIPT_MISMATCH");
        if("COMMITTED".equals(receipt.state())) {
            if(creation) {
                var container=receipt.container();
                if(!"READY".equals(receipt.effectState())||container==null||!container.running()
                        ||!container.id().matches("[0-9a-f]{64}")||!command.imageId().equals(container.image())||!command.policyHash().equals(container.policyHash())
                        ||container.readinessHash()==null||!container.readinessHash().matches("[0-9a-f]{64}")||container.startedAt()==null||container.startedAt().startsWith("0001")||receipt.stop()!=null)
                    throw new WorkspaceFailure(409,"RUNNER_READINESS_MISMATCH");
            } else if(!"STOPPED".equals(receipt.effectState())||receipt.stop()==null||receipt.container()!=null)throw new WorkspaceFailure(409,"RUNNER_STOP_MISMATCH");
        } else if("FAILED".equals(receipt.state())) {
            if(receipt.effectState()==null||!Set.of("NOT_STARTED","STOPPED").contains(receipt.effectState())||receipt.container()!=null
                    ||"NOT_STARTED".equals(receipt.effectState())&&(!creation||receipt.stop()!=null)
                    ||"STOPPED".equals(receipt.effectState())&&receipt.stop()==null)throw new WorkspaceFailure(409,"RUNNER_FAILURE_UNPROVEN");
        } else if("UNKNOWN".equals(receipt.state())&&!"MAY_HAVE_STARTED".equals(receipt.effectState()))throw new WorkspaceFailure(409,"RUNNER_RECEIPT_MISMATCH");
        if(receipt.stop()!=null) {
            var stop=receipt.stop();
            if(!stop.execStopped()||!stop.callbacksStopped()||!command.imageId().equals(stop.image())||stop.containerId()==null
                    ||!stop.containerId().matches("[0-9a-f]{64}")||stop.finishedAt()==null||stop.finishedAt().startsWith("0001")||stop.observedAt()==null
                    ||command.containerId()!=null&&!command.containerId().equals(stop.containerId())
                    ||command.containerStartedAt()!=null&&!command.containerStartedAt().equals(stop.startedAt()))throw new WorkspaceFailure(409,"RUNNER_STOP_MISMATCH");
        }
    }
    @Override public void commitReceipt(Command command,Receipt receipt,UUID runnerId) {
        guard(command,true);checkRunner(command,runnerId);validate(command,receipt);
        if(receipt.revision()!=1)throw new WorkspaceFailure(409,"RUNNER_RECEIPT_MISMATCH");
        var prior=mapper.priorStop(command);
        if(prior!=null&&!prior.equals(receipt.stop()))throw new WorkspaceFailure(409,"RUNNER_STOP_PROVENANCE_MISMATCH");
        var existing=mapper.receipt(command.commandId());
        String json=JSON.writeValueAsString(receipt);String hash=RunnerSignatures.sha256(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if(existing!=null) {
            if(!existing.contentHash().equals(hash))throw new WorkspaceFailure(409,"RUNNER_RECEIPT_CONFLICT");
            return;
        }
        if(mapper.commitRunner(command,receipt,runnerId)!=1)throw new WorkspaceFailure(409,"RUNNER_RECEIPT_CONFLICT");
        mapper.insertReceipt(command,receipt,json,hash);
        if(receipt.stop()!=null) {
            var original=mapper.originalRunner(command.commandId());
            mapper.insertStop(Uuid7.next(),command,receipt,RunnerSignatures.sha256(JSON.writeValueAsBytes(receipt.stop())),runnerId,runnerId.equals(original)?null:original);
        }
    }
    @Override public Receipt confirmCleanup(Command command,SandboxRuntime.Removal removal,UUID runnerId) {
        guard(command,true);checkRunner(command,runnerId);
        return cleanupFacts(command,removal,runnerId);
    }
    @Override public Receipt confirmObservedCleanup(Command command,SandboxRuntime.Removal removal,UUID runnerId) {
        observedGuard(command,null,null,true);checkObservedRunner(command,runnerId);
        return cleanupFacts(command,removal,runnerId);
    }
    private Receipt cleanupFacts(Command command,SandboxRuntime.Removal removal,UUID runnerId) {
        if(!"ENVIRONMENT_V3".equals(command.protocol())||!Set.of("STOP","SLEEP","DELETE").contains(command.kind())) {
            throw new WorkspaceFailure(409,"ENVIRONMENT_CLEANUP_UNSUPPORTED");
        }
        var stopped=readReceipt(command);var stop=stopped.stop();
        if(stop==null||!Set.of("COMMITTED","FAILED").contains(stopped.state())||removal==null
                ||!stop.containerId().equals(removal.containerId())||!stop.image().equals(removal.image())
                ||!stop.startedAt().equals(removal.startedAt())||!stop.finishedAt().equals(removal.finishedAt())
                ||removal.observedAt()==null||removal.observedAt().isBefore(stop.observedAt())) {
            throw new WorkspaceFailure(409,"ENVIRONMENT_CLEANUP_MISMATCH");
        }
        if(stopped.revision()==2) {
            if(!mapper.cleanupConfirmed(command))throw new WorkspaceFailure(503,"ENVIRONMENT_CLEANUP_CORRUPT");
            return stopped;
        }
        var stopId=mapper.stopReceiptId(command);
        if(stopId==null)throw new WorkspaceFailure(409,"ENVIRONMENT_STOP_UNPROVEN");
        String json=JSON.writeValueAsString(removal),hash=RunnerSignatures.sha256(JSON.writeValueAsBytes(removal));
        mapper.insertCleanup(Uuid7.next(),command,stopId,runnerId,removal,json,hash);
        if(mapper.commitCleanup(command,runnerId)!=1)throw new WorkspaceFailure(409,"ENVIRONMENT_CLEANUP_CONFLICT");
        var confirmed=new Receipt(command.commandId(),command.operationId(),command.environmentId(),command.workspaceId(),
                command.generation(),command.fence(),stopped.state(),stopped.effectState(),stopped.errorCode(),null,stop,2);
        String receiptJson=JSON.writeValueAsString(confirmed);
        mapper.insertReceipt(command,confirmed,receiptJson,RunnerSignatures.sha256(JSON.writeValueAsBytes(confirmed)));
        return confirmed;
    }
    @Override public void complete(Command command,Receipt receipt) {
        guard(command,true);validate(command,receipt);
        requireCanonical(command,receipt);
        project(command,receipt,mapper.completeOperation(command,receipt));
    }
    @Override public void completeObserved(Observation observation,Receipt receipt) {
        var command=observation.command();observedGuard(command,observation.observerId(),observation.revision(),false);
        if(receipt==null||!Set.of("COMMITTED","FAILED").contains(receipt.state()))throw new WorkspaceFailure(409,"ENVIRONMENT_OBSERVATION_UNCONFIRMED");
        requireCanonical(command,receipt);
        var operation=environments.operation(command.operationId());
        if(Set.of("COMMITTED","FAILED").contains(operation.status())) {
            if(!operation.status().equals(receipt.state()))throw new WorkspaceFailure(409,"ENVIRONMENT_OBSERVATION_CONFLICT");
            return;
        }
        observedGuard(command,observation.observerId(),observation.revision(),true);
        project(command,receipt,mapper.completeObservedOperation(observation,receipt));
    }
    private void requireCanonical(Command command,Receipt receipt) {
        validate(command,receipt);
        if("ENVIRONMENT_V3".equals(command.protocol())&&Set.of("STOP","SLEEP","DELETE").contains(command.kind())
                &&(receipt.revision()!=2||!mapper.cleanupConfirmed(command))) {
            throw new WorkspaceFailure(409,"ENVIRONMENT_CLEANUP_UNCONFIRMED");
        }
        var stored=readReceipt(command);
        if(!receipt.equals(stored))throw new WorkspaceFailure(409,"RUNNER_RECEIPT_MISMATCH");
    }
    private void project(Command command,Receipt receipt,int changed) {
        String state="UNKNOWN".equals(receipt.state())?"BLOCKED":"FAILED".equals(receipt.state())?"ERROR":
                "DELETE".equals(command.kind())?"DELETED":Set.of("STOP","SLEEP").contains(command.kind())?"STOPPED":"READY";
        if(changed!=1||mapper.projectEnvironment(command,receipt,state)!=1)throw new WorkspaceFailure(409,"ENVIRONMENT_STALE_OWNER");
        mapper.projectWorkspace(command,state);
        if(Set.of("STOPPED","DELETED").contains(state)||"ERROR".equals(state)&&("NOT_STARTED".equals(receipt.effectState())||receipt.stop()!=null))mapper.releaseSlot(command);
        event(environments.environment(command.spaceId(),command.workspaceId()));
    }
    @Override public void uncertain(Command command,String code) {
        guard(command,true);
        var receipt=new Receipt(command.commandId(),command.operationId(),command.environmentId(),command.workspaceId(),command.generation(),command.fence(),"UNKNOWN","MAY_HAVE_STARTED",code,null,null,1);
        if(mapper.uncertain(command,code)!=1||mapper.projectEnvironment(command,receipt,"BLOCKED")!=1)throw new WorkspaceFailure(409,"ENVIRONMENT_STALE_OWNER");
        mapper.projectWorkspace(command,"BLOCKED");event(environments.environment(command.spaceId(),command.workspaceId()));
    }
    @Override public void quarantine(UUID id,String reason,String hash) {tx();workspaces.quarantine(id,reason,hash);}
}
