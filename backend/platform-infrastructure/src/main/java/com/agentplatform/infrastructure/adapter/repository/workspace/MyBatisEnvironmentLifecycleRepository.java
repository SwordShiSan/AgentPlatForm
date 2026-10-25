package com.agentplatform.infrastructure.adapter.repository.workspace;

import com.agentplatform.domain.workspace.EnvironmentLifecycleRepository;
import com.agentplatform.domain.workspace.WorkspaceData.Actor;
import com.agentplatform.domain.workspace.WorkspaceFailure;
import com.agentplatform.infrastructure.dao.po.EnvironmentLifecycleRows.Plan;
import com.agentplatform.infrastructure.dao.po.EnvironmentRows.Environment;
import com.agentplatform.infrastructure.dao.workspace.EnvironmentLifecycleMapper;
import com.agentplatform.infrastructure.dao.workspace.EnvironmentPreparationMapper;
import com.agentplatform.infrastructure.dao.workspace.WorkspaceMapper;
import com.agentplatform.types.Uuid7;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** V3 admission ledger. No network, Docker or file I/O belongs in this transaction. */
public final class MyBatisEnvironmentLifecycleRepository implements EnvironmentLifecycleRepository {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final EnvironmentLifecycleMapper lifecycle;
    private final EnvironmentPreparationMapper environments;
    private final WorkspaceMapper workspaces;
    private final String image;
    private final String policy;
    private final MyBatisEnvironmentPreparationRepository preparation;

    public MyBatisEnvironmentLifecycleRepository(EnvironmentLifecycleMapper lifecycle,
            EnvironmentPreparationMapper environments, WorkspaceMapper workspaces, String image, String policy) {
        if (image == null || !image.matches("sha256:[0-9a-f]{64}") || policy == null || !policy.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Pinned environment policy required");
        }
        this.lifecycle = lifecycle; this.environments = environments; this.workspaces = workspaces;
        this.image = image; this.policy = policy;
        this.preparation = new MyBatisEnvironmentPreparationRepository(environments,workspaces,image,policy);
    }

    private static WorkspaceFailure conflict(String code) { return new WorkspaceFailure(409, code); }

    @Override public Snapshot get(Actor actor,UUID workspaceId) {
        var base=preparation.get(actor,workspaceId);
        if(base.environmentId()==null)return base;
        var target=lifecycle.target(base.environmentId());
        if(target==null)throw new WorkspaceFailure(503,"ENVIRONMENT_STORAGE_UNAVAILABLE");
        if(target.pendingOperationId()!=null) {
            var pending=lifecycle.pending(target.pendingOperationId());
            if(pending==null)throw new WorkspaceFailure(503,"ENVIRONMENT_STORAGE_UNAVAILABLE");
            return new Snapshot(base.environmentId(),workspaceId,base.state(),base.generation(),base.version(),pending.id(),pending.kind(),
                    pending.status(),base.errorCode(),false,false,false,false,false);
        }
        var slot=environments.lockSlot();
        var environment=environments.lockEnvironment(actor.spaceId(),workspaceId);
        var current=environments.operation(environment.operationId());
        var use=lifecycle.activeUse(base.environmentId());
        boolean quiet="READY".equals(workspaces.workspace(actor.spaceId(),workspaceId).state())
                &&!workspaces.fileDataPlaneBusy(actor.spaceId(),workspaceId)&&!lifecycle.fileOperationBusy(actor.spaceId(),workspaceId)
                &&(use==null||!use.kind().startsWith("FILE_")&&!"UNKNOWN".equals(use.status()));
        boolean ready=quiet&&"READY".equals(base.state())&&"COMMITTED".equals(current.status())
                &&Set.of("PREPARE","WAKE").contains(current.kind())&&environment.id().equals(slot.environmentId())
                &&slot.generation()!=null&&slot.generation()==base.generation();
        boolean stopped=quiet&&use==null&&slot.environmentId()==null&&"STOPPED".equals(base.state())
                &&"COMMITTED".equals(current.status())&&"STOPPED".equals(current.effectState())
                &&target.priorStopReceiptId()!=null&&environments.cleanupSatisfied(current.id());
        return new Snapshot(base.environmentId(),workspaceId,base.state(),base.generation(),base.version(),base.operationId(),
                base.operationKind(),base.operationStatus(),base.errorCode(),base.prepareAllowed()&&!"STOPPED".equals(base.state()),
                base.retryAllowed(),ready,stopped,ready||stopped);
    }

    @Override public Operation operation(Actor actor,UUID workspaceId,UUID operationId) {
        preparation.get(actor,workspaceId); // Same scoped live authorization and consistent parent locks.
        var result=lifecycle.operationView(actor,workspaceId,operationId);
        if(result==null)throw new WorkspaceFailure(404,"RESOURCE_NOT_FOUND");return result;
    }

    @Override public Operation request(Actor actor,UUID workspaceId,UUID requestId) {
        preparation.get(actor,workspaceId);
        var result=lifecycle.requestView(actor,workspaceId,requestId);
        if(result==null)throw new WorkspaceFailure(404,"RESOURCE_NOT_FOUND");return result;
    }

    @Override public boolean activateNext() {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Environment lifecycle transaction required");
        var candidate=lifecycle.nextPending();
        return candidate!=null&&activatePending(candidate.spaceId(),candidate.workspaceId());
    }

    @Override public Submission prepare(Actor actor,UUID workspaceId,UUID requestId,String hash,long version,boolean retry) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Environment lifecycle transaction required");
        if(requestId==null||version<0||hash==null||!hash.matches("[0-9a-f]{64}"))throw new WorkspaceFailure(400,"INVALID_ENVIRONMENT_REQUEST");
        environments.lockSlot();
        var workspace=workspaces.lockWorkspace(actor.spaceId(),workspaceId);
        if(!workspaces.authorized(actor)||workspace==null)throw new WorkspaceFailure(404,"RESOURCE_NOT_FOUND");
        var current=environments.lockEnvironment(actor.spaceId(),workspaceId);
        var previous=environments.request(actor,requestId);
        if(previous!=null) {
            if(!workspaceId.equals(previous.workspaceId())||!hash.equals(previous.requestHash()))throw conflict("IDEMPOTENCY_CONFLICT");
            return new Submission(previous.operationId(),get(actor,workspaceId));
        }
        String kind=retry&&current!=null?environments.operation(current.operationId()).kind():"PREPARE";
        if(retry&&Set.of("STOP","SLEEP","DELETE").contains(kind)) {
            var admitted=admit(actor,workspaceId,requestId,hash,version,Intent.valueOf(kind),true);
            return new Submission(admitted.operationId(),get(actor,workspaceId));
        }
        if(!Set.of("PREPARE","WAKE").contains(kind))throw conflict("ENVIRONMENT_RETRY_UNSAFE");
        // Reuse the existing short-transaction admission helper, not a second live
        // writer. Freeze V3 before this same transaction exposes its pending outbox.
        var admitted=preparation.prepare(actor,workspaceId,requestId,hash,version,retry);
        if(lifecycle.freezeCreation(admitted.operationId(),kind)!=1)throw conflict("ENVIRONMENT_CONCURRENT_CHANGE");
        return new Submission(admitted.operationId(),get(actor,workspaceId));
    }

    @Override public boolean activatePending(UUID spaceId, UUID workspaceId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Environment lifecycle transaction required");
        }
        var slot=environments.lockSlot();
        if(workspaces.lockWorkspace(spaceId,workspaceId)==null)return false;
        var environment=environments.lockEnvironment(spaceId,workspaceId);
        if(environment==null)return false;
        var target=lifecycle.target(environment.id());
        if(target.pendingOperationId()==null)return false;
        var pending=lifecycle.pending(target.pendingOperationId());
        if(pending==null||!"DRAINING".equals(pending.status())||pending.generation()!=environment.generation()
                ||!environment.id().equals(slot.environmentId())||slot.generation()==null
                ||slot.generation()!=environment.generation())return false;
        if(workspaces.fileDataPlaneBusy(spaceId,workspaceId)||lifecycle.fileOperationBusy(spaceId,workspaceId))return false;
        var active=lifecycle.activeUse(environment.id());
        if(active!=null) {
            // A cancel request is not a stop fact, nor permission to move the fence.
            if(!active.kind().startsWith("FILE_")&&!"UNKNOWN".equals(active.status()))lifecycle.cancelSandboxUse(active.id());
            return false;
        }
        var current=environments.operation(environment.operationId());
        if(!"COMMITTED".equals(current.status())||!("READY".equals(current.effectState())
                ||"STOPPED".equals(current.effectState())&&target.priorStopReceiptId()!=null))return false;
        if(!pending.creationCommandId().equals(environment.creationCommandId())
                ||pending.creationFence()!=environment.creationFence()
                ||!pending.imageId().equals(environment.imageId())||!pending.policyHash().equals(environment.policyHash())
                ||!pending.containerId().equals(target.containerId())
                ||!pending.containerStartedAt().equals(target.containerStartedAt()))return false;
        long fence=Math.addExact(environment.fence(),1);
        UUID prior="STOPPED".equals(current.effectState())?target.priorStopReceiptId():null;
        var activated=new Plan(pending.id(),spaceId,workspaceId,environment.id(),pending.actorId(),pending.commandId(),
                pending.generation(),fence,pending.kind(),"PENDING",pending.creationCommandId(),pending.creationFence(),
                pending.imageId(),pending.policyHash(),pending.projectId(),pending.containerId(),pending.containerStartedAt(),prior);
        String phase="DELETE".equals(pending.kind())?"DELETING":"STOPPING";
        if(lifecycle.activate(pending.id(),fence,prior)!=1||lifecycle.selectPending(activated,environment.version(),phase)!=1) {
            throw conflict("ENVIRONMENT_CONCURRENT_CHANGE");
        }
        var next=new Environment(environment.id(),spaceId,workspaceId,environment.generation(),fence,phase,
                environment.imageId(),environment.policyHash(),environment.creationCommandId(),environment.creationFence(),
                pending.id(),environment.version()+1,null);
        environments.insertOutbox(Uuid7.next(),next);
        lifecycle.projectWorkspace(activated,phase);
        var payload=new LinkedHashMap<String,Object>();
        payload.put("workspaceId",workspaceId.toString());payload.put("state",phase);
        payload.put("generation",next.generation());payload.put("version",next.version());
        payload.put("operationId",pending.id().toString());payload.put("errorCode",null);
        environments.insertEvent(Uuid7.next(),next,JSON.writeValueAsString(payload));
        return true;
    }

    @Override public Admission admit(Actor actor, UUID workspaceId, UUID requestId, String hash,
            long version, Intent intent) {
        if(intent==Intent.STOP)throw new WorkspaceFailure(400,"INVALID_ENVIRONMENT_REQUEST");
        return admit(actor,workspaceId,requestId,hash,version,intent,false);
    }

    private Admission admit(Actor actor,UUID workspaceId,UUID requestId,String hash,long version,Intent intent,boolean retry) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Environment lifecycle transaction required");
        }
        if (requestId == null || intent == null || version < 0 || hash == null || !hash.matches("[0-9a-f]{64}")) {
            throw new WorkspaceFailure(400, "INVALID_ENVIRONMENT_REQUEST");
        }
        // Global capacity -> scoped workspace -> environment -> operation/use.
        var slot = environments.lockSlot();
        var workspace = workspaces.lockWorkspace(actor.spaceId(), workspaceId);
        if (!workspaces.authorized(actor) || workspace == null) throw new WorkspaceFailure(404, "RESOURCE_NOT_FOUND");
        var environment = environments.lockEnvironment(actor.spaceId(), workspaceId);
        var previous = environments.request(actor, requestId);
        if (previous != null) {
            if (!workspaceId.equals(previous.workspaceId()) || !hash.equals(previous.requestHash())) {
                throw conflict("IDEMPOTENCY_CONFLICT");
            }
            var original = lifecycle.original(actor, requestId);
            if (original == null || !intent.name().equals(original.intent())) throw conflict("IDEMPOTENCY_CONFLICT");
            return new Admission(original.operationId(), original.intent(), original.status(), original.generation(), original.version());
        }
        if (environment == null) throw conflict("ENVIRONMENT_NOT_CREATED");
        if (environment.version() != version) throw new WorkspaceFailure(412, "VERSION_CONFLICT");
        if (!"READY".equals(workspace.state())) throw conflict("WORKSPACE_STORAGE_NOT_READY");
        var target = lifecycle.target(environment.id());
        var operation = environments.operation(environment.operationId());
        if (target.pendingOperationId() != null || Set.of("PREPARING", "STOPPING", "DELETING").contains(environment.state())) {
            throw new WorkspaceFailure(423, "ENVIRONMENT_LIFECYCLE_BUSY");
        }
        if (workspaces.fileDataPlaneBusy(actor.spaceId(), workspaceId) || lifecycle.fileOperationBusy(actor.spaceId(), workspaceId)) {
            throw new WorkspaceFailure(423, "WORKSPACE_DATA_PLANE_BUSY");
        }
        var use = lifecycle.activeUse(environment.id());
        if (use != null && (use.kind().startsWith("FILE_") || "UNKNOWN".equals(use.status()))) {
            throw new WorkspaceFailure(423, "WORKSPACE_DATA_PLANE_BUSY");
        }
        boolean ready = "READY".equals(environment.state()) && "COMMITTED".equals(operation.status())
                && Set.of("PREPARE", "WAKE").contains(operation.kind());
        boolean stopped = "STOPPED".equals(environment.state()) && "COMMITTED".equals(operation.status())
                && "STOPPED".equals(operation.effectState()) && target.priorStopReceiptId() != null
                && (!"ENVIRONMENT_V3".equals(target.protocol()) || "CONFIRMED".equals(target.cleanupState()));
        boolean failed=retry&&"ERROR".equals(environment.state())&&"FAILED".equals(operation.status())
                &&operation.kind().equals(intent.name())&&"STOPPED".equals(operation.effectState())
                &&target.priorStopReceiptId()!=null&&environments.cleanupSatisfied(operation.id());
        if (!(retry?failed:intent == Intent.WAKE ? stopped : ready || intent == Intent.DELETE && stopped)) {
            throw conflict("ENVIRONMENT_LIFECYCLE_UNSAFE");
        }
        if (slot.environmentId() != null && !slot.environmentId().equals(environment.id())) {
            throw conflict("SANDBOX_CAPACITY_UNAVAILABLE");
        }
        if (ready && (!environment.id().equals(slot.environmentId()) || slot.generation() == null
                || slot.generation() != environment.generation())) throw conflict("ENVIRONMENT_LIFECYCLE_UNSAFE");
        if ((stopped||failed) && (use != null || slot.environmentId() != null)) throw conflict("ENVIRONMENT_LIFECYCLE_UNSAFE");

        boolean wake = intent == Intent.WAKE;
        boolean draining = use != null;
        long generation = wake ? Math.addExact(environment.generation(), 1) : environment.generation();
        // An active old execution retains its fence until its stop barrier commits.
        long fence = draining ? environment.fence() : Math.addExact(environment.fence(), 1);
        UUID operationId = Uuid7.next(), commandId = Uuid7.next();
        var next = new Environment(environment.id(), actor.spaceId(), workspaceId, generation, fence,
                wake ? "PREPARING" : intent == Intent.DELETE ? "DELETING" : "STOPPING",
                wake ? image : environment.imageId(), wake ? policy : environment.policyHash(),
                wake ? commandId : environment.creationCommandId(), wake ? fence : environment.creationFence(),
                operationId, version + 1, null);
        var plan = new Plan(operationId, actor.spaceId(), workspaceId, environment.id(), actor.userId(), commandId,
                generation, fence, intent.name(), draining ? "DRAINING" : "PENDING", next.creationCommandId(),
                next.creationFence(), next.imageId(), next.policyHash(), workspace.projectId(),
                wake ? null : target.containerId(), wake ? null : target.containerStartedAt(),
                (stopped||failed) && !wake ? target.priorStopReceiptId() : null);
        if (!wake && (plan.containerId() == null || plan.containerStartedAt() == null)) {
            throw conflict("ENVIRONMENT_STOP_IDENTITY_MISSING");
        }
        lifecycle.insertOperation(plan);
        int selected = wake ? environments.replaceGeneration(next, version)
                : lifecycle.selectOperation(plan, version, next.state());
        if (selected != 1) throw conflict("ENVIRONMENT_CONCURRENT_CHANGE");
        if (environments.reserveSlot(next, slot.revision()) != 1) throw conflict("SANDBOX_CAPACITY_UNAVAILABLE");
        environments.insertRequest(actor, requestId, hash, next);
        if (!draining) environments.insertOutbox(Uuid7.next(), next);
        lifecycle.projectWorkspace(plan, next.state());
        var payload = new LinkedHashMap<String, Object>();
        payload.put("workspaceId", workspaceId.toString()); payload.put("state", next.state());
        payload.put("generation", generation); payload.put("version", next.version());
        payload.put("operationId", operationId.toString()); payload.put("errorCode", null);
        environments.insertEvent(Uuid7.next(), next, JSON.writeValueAsString(payload));
        return new Admission(operationId, intent.name(), plan.status(), generation, next.version());
    }
}
