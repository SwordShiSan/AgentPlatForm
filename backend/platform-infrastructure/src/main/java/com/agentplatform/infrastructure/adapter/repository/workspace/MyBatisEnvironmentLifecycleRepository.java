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

    public MyBatisEnvironmentLifecycleRepository(EnvironmentLifecycleMapper lifecycle,
            EnvironmentPreparationMapper environments, WorkspaceMapper workspaces, String image, String policy) {
        if (image == null || !image.matches("sha256:[0-9a-f]{64}") || policy == null || !policy.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Pinned environment policy required");
        }
        this.lifecycle = lifecycle; this.environments = environments; this.workspaces = workspaces;
        this.image = image; this.policy = policy;
    }

    private static WorkspaceFailure conflict(String code) { return new WorkspaceFailure(409, code); }

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
        if (!(intent == Intent.WAKE ? stopped : ready || intent == Intent.DELETE && stopped)) {
            throw conflict("ENVIRONMENT_LIFECYCLE_UNSAFE");
        }
        if (slot.environmentId() != null && !slot.environmentId().equals(environment.id())) {
            throw conflict("SANDBOX_CAPACITY_UNAVAILABLE");
        }
        if (ready && (!environment.id().equals(slot.environmentId()) || slot.generation() == null
                || slot.generation() != environment.generation())) throw conflict("ENVIRONMENT_LIFECYCLE_UNSAFE");
        if (stopped && (use != null || slot.environmentId() != null)) throw conflict("ENVIRONMENT_LIFECYCLE_UNSAFE");

        boolean wake = intent == Intent.WAKE;
        boolean draining = use != null;
        long generation = wake ? Math.addExact(environment.generation(), 1) : environment.generation();
        // An active old execution retains its fence until its stop barrier commits.
        long fence = draining ? environment.fence() : Math.addExact(environment.fence(), 1);
        UUID operationId = Uuid7.next(), commandId = Uuid7.next();
        var next = new Environment(environment.id(), actor.spaceId(), workspaceId, generation, fence,
                wake ? "PREPARING" : intent == Intent.SLEEP ? "STOPPING" : "DELETING",
                wake ? image : environment.imageId(), wake ? policy : environment.policyHash(),
                wake ? commandId : environment.creationCommandId(), wake ? fence : environment.creationFence(),
                operationId, version + 1, null);
        var plan = new Plan(operationId, actor.spaceId(), workspaceId, environment.id(), actor.userId(), commandId,
                generation, fence, intent.name(), draining ? "DRAINING" : "PENDING", next.creationCommandId(),
                next.creationFence(), next.imageId(), next.policyHash(), workspace.projectId(),
                wake ? null : target.containerId(), wake ? null : target.containerStartedAt(),
                stopped && !wake ? target.priorStopReceiptId() : null);
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
