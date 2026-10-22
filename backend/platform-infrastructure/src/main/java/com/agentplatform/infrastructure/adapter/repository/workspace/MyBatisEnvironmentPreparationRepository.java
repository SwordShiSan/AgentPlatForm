package com.agentplatform.infrastructure.adapter.repository.workspace;

import com.agentplatform.domain.workspace.EnvironmentPreparationRepository;
import com.agentplatform.domain.workspace.WorkspaceData.Actor;
import com.agentplatform.domain.workspace.WorkspaceFailure;
import com.agentplatform.infrastructure.dao.po.EnvironmentRows.*;
import com.agentplatform.infrastructure.dao.workspace.EnvironmentPreparationMapper;
import com.agentplatform.infrastructure.dao.workspace.WorkspaceMapper;
import com.agentplatform.types.Uuid7;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Scoped admission facts only. The outbox is the sole execution handoff. */
public final class MyBatisEnvironmentPreparationRepository implements EnvironmentPreparationRepository {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final EnvironmentPreparationMapper mapper;
    private final WorkspaceMapper workspaces;
    private final String image;
    private final String policy;

    public MyBatisEnvironmentPreparationRepository(EnvironmentPreparationMapper mapper, WorkspaceMapper workspaces,
            String image, String policy) {
        if (image == null || !image.matches("sha256:[0-9a-f]{64}") || policy == null || !policy.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Pinned environment policy required");
        }
        this.mapper = mapper; this.workspaces = workspaces; this.image = image; this.policy = policy;
    }

    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Environment transaction required");
    }

    private com.agentplatform.infrastructure.dao.po.WorkspaceRows.WorkspaceRow workspace(Actor actor, UUID workspaceId) {
        var row = workspaces.lockWorkspace(actor.spaceId(), workspaceId);
        // The scoped authorization function holds the identity/membership row locks.
        // Do not add an actor advisory lock after the workspace lock: older storage
        // transactions acquire that advisory lock first, which would invert order.
        if (!workspaces.authorized(actor) || row == null) throw new WorkspaceFailure(404, "RESOURCE_NOT_FOUND");
        return row;
    }

    private Snapshot snapshot(UUID workspaceId, Environment environment) {
        if (environment == null) return new Snapshot(null, workspaceId, "NOT_CREATED", 0, 0, null, null, null, null);
        var operation = mapper.operation(environment.operationId());
        return new Snapshot(environment.id(), workspaceId, environment.state(), environment.generation(), environment.version(),
                environment.operationId(), operation.kind(), operation.status(), environment.errorCode());
    }

    @Override public Snapshot get(Actor actor, UUID workspaceId) {
        requireTransaction();
        mapper.lockSlot(); // Same lock order for a consistent multi-row projection.
        workspace(actor, workspaceId);
        return snapshot(workspaceId, mapper.lockEnvironment(actor.spaceId(), workspaceId));
    }

    @Override public Submission prepare(Actor actor, UUID workspaceId, UUID requestId, String hash, long version, boolean retry) {
        requireTransaction();
        if (version < 0 || hash == null || !hash.matches("[0-9a-f]{64}")) throw new WorkspaceFailure(400, "INVALID_ENVIRONMENT_REQUEST");
        var slot = mapper.lockSlot();
        var workspace = workspace(actor, workspaceId);
        var environment = mapper.lockEnvironment(actor.spaceId(), workspaceId);
        var previous = mapper.request(actor, requestId);
        if (previous != null) {
            if (!workspaceId.equals(previous.workspaceId()) || !hash.equals(previous.requestHash())) {
                throw new WorkspaceFailure(409, "IDEMPOTENCY_CONFLICT");
            }
            return new Submission(previous.operationId(), snapshot(workspaceId, environment));
        }
        if (!"READY".equals(workspace.state())) throw new WorkspaceFailure(409, "WORKSPACE_STORAGE_NOT_READY");
        if (version != (environment == null ? 0 : environment.version())) throw new WorkspaceFailure(412, "VERSION_CONFLICT");
        if (environment == null) {
            if (retry) throw new WorkspaceFailure(409, "ENVIRONMENT_RETRY_UNSAFE");
        } else {
            var operation = mapper.operation(environment.operationId());
            boolean stopped = "STOPPED".equals(environment.state()) && "STOP".equals(operation.kind())
                    && "COMMITTED".equals(operation.status()) && "STOPPED".equals(operation.effectState())
                    && mapper.confirmedStop(operation.id());
            boolean failed = "ERROR".equals(environment.state()) && "FAILED".equals(operation.status())
                    && ("NOT_STARTED".equals(operation.effectState()) || mapper.confirmedStop(operation.id()));
            if (!(retry ? failed : stopped) || mapper.unresolvedUse(environment.id())) {
                throw new WorkspaceFailure(409, "ENVIRONMENT_RETRY_UNSAFE");
            }
        }
        UUID environmentId = environment == null ? Uuid7.next() : environment.id();
        if (slot.environmentId() != null && !slot.environmentId().equals(environmentId)) {
            throw new WorkspaceFailure(409, "SANDBOX_CAPACITY_UNAVAILABLE");
        }
        long generation = environment == null ? 1 : Math.addExact(environment.generation(), 1);
        long fence = environment == null ? 1 : Math.addExact(environment.fence(), 1);
        var next = new Environment(environmentId, actor.spaceId(), workspaceId, generation, fence, "PREPARING",
                image, policy, Uuid7.next(), fence, Uuid7.next(), version + 1, null);
        if (environment == null) mapper.insertEnvironment(next);
        else if (mapper.replaceGeneration(next, version) != 1) throw new WorkspaceFailure(409, "ENVIRONMENT_CONCURRENT_CHANGE");
        mapper.insertOperation(next, actor.userId());
        if (mapper.reserveSlot(next, slot.revision()) != 1) throw new WorkspaceFailure(409, "SANDBOX_CAPACITY_UNAVAILABLE");
        mapper.insertRequest(actor, requestId, hash, next);
        mapper.insertOutbox(Uuid7.next(), next);
        mapper.projectWorkspace(actor.spaceId(), workspaceId);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("workspaceId", workspaceId.toString()); payload.put("state", "PREPARING");
        payload.put("generation", generation); payload.put("version", next.version());
        payload.put("operationId", next.operationId().toString()); payload.put("errorCode", null);
        mapper.insertEvent(Uuid7.next(), next, JSON.writeValueAsString(payload));
        return new Submission(next.operationId(), snapshot(workspaceId, next));
    }
}
