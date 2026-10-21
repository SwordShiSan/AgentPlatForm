package com.agentplatform.infrastructure.adapter.repository.workspace;

import com.agentplatform.domain.workspace.*;
import com.agentplatform.domain.workspace.WorkspaceData.*;
import com.agentplatform.infrastructure.dao.workspace.WorkspaceMapper;
import com.agentplatform.infrastructure.dao.po.WorkspaceRows.*;
import com.agentplatform.infrastructure.workspace.RunnerSignatures;
import com.agentplatform.types.Uuid7;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Typed persistence only. Application transactions enclose every method; no network calls here. */
public final class MyBatisWorkspaceRepository implements WorkspaceRepository {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final WorkspaceMapper mapper;
    public MyBatisWorkspaceRepository(WorkspaceMapper mapper) { this.mapper = mapper; }
    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Workspace transaction required");
    }
    private void authorize(Actor actor) {
        mapper.actorLock(actor.userId().getLeastSignificantBits());
        if (!mapper.authorized(actor)) throw new WorkspaceFailure(404, "RESOURCE_NOT_FOUND");
    }
    private static Workspace view(WorkspaceRow row) {
        if (row == null) throw new WorkspaceFailure(404, "RESOURCE_NOT_FOUND");
        return new Workspace(row.id(), row.spaceId(), row.actorId(), row.name(), row.state(), row.environmentState(),
                row.quotaBytes(), row.quotaInodes(), row.version(), row.operationId(), row.errorCode(), row.createdAt());
    }
    private WorkspaceRow locked(UUID space, UUID id) {
        var row = mapper.lockWorkspace(space, id); view(row); return row;
    }
    private void event(WorkspaceRow row) {
        Map<String,Object> payload = new LinkedHashMap<>();
        payload.put("workspaceId", row.id().toString()); payload.put("state", row.state());
        payload.put("environmentState", row.environmentState()); payload.put("operationId", row.operationId().toString());
        payload.put("errorCode", row.errorCode());
        mapper.insertEvent(Uuid7.next(), row, JSON.writeValueAsString(payload));
    }
    private Workspace existing(Actor actor, UUID key, String hash) {
        var previous = mapper.request(actor, key);
        if (previous == null) return null;
        if (!hash.equals(previous.requestHash())) throw new WorkspaceFailure(409, "IDEMPOTENCY_CONFLICT");
        return view(mapper.workspace(actor.spaceId(), previous.workspaceId()));
    }
    @Override public Workspace create(Actor actor, UUID key, String hash, String name) {
        requireTransaction(); var capacity = mapper.lockCapacity(); authorize(actor);
        Workspace previous = existing(actor, key, hash); if (previous != null) return previous;
        if (capacity.reservedSlots() != 0 || capacity.reservedBytes() != 0
                || mapper.reserveCapacity(capacity.nextProjectId()) != 1) throw new WorkspaceFailure(409, "WORKSPACE_CAPACITY_UNAVAILABLE");
        UUID id = Uuid7.next(), operation = Uuid7.next(), command = Uuid7.next();
        var row = new WorkspaceRow(id, actor.spaceId(), actor.userId(), name, "PROVISIONING", "NOT_CREATED",
                WorkspaceData.QUOTA_BYTES, WorkspaceData.QUOTA_INODES, capacity.nextProjectId(), 1, operation, null, null);
        mapper.insertWorkspace(row);
        mapper.insertOperation(operation, actor.spaceId(), id, actor.userId(), command, 1, 1);
        mapper.reserveWorkspace(actor.spaceId(), id);
        mapper.insertRequest(actor, key, hash, id, operation);
        mapper.insertOutbox(Uuid7.next(), actor.spaceId(), id, operation, 1, 1);
        row = mapper.workspace(actor.spaceId(), id); event(row); return view(row);
    }
    @Override public Workspace get(Actor actor, UUID id) {
        requireTransaction(); authorize(actor); return view(mapper.workspace(actor.spaceId(), id));
    }
    @Override public List<Workspace> list(Actor actor, UUID before, int limit) {
        requireTransaction(); authorize(actor); return mapper.list(actor.spaceId(), before, limit).stream().map(MyBatisWorkspaceRepository::view).toList();
    }
    @Override public Workspace retry(Actor actor, UUID id, UUID key, String hash, long version) {
        requireTransaction(); mapper.lockCapacity(); authorize(actor);
        Workspace previous = existing(actor, key, hash); if (previous != null) return previous;
        var row = locked(actor.spaceId(), id); var op = mapper.operation(row.operationId());
        if (row.version() != version) throw new WorkspaceFailure(412, "VERSION_CONFLICT");
        if (!row.state().equals("ERROR") || !op.status().equals("FAILED") || !op.effectState().equals("NOT_STARTED"))
            throw new WorkspaceFailure(409, "WORKSPACE_RETRY_UNSAFE");
        UUID operation = Uuid7.next(); long generation = Math.addExact(op.generation(), 1), fence = Math.addExact(op.fence(), 1);
        mapper.insertOperation(operation, actor.spaceId(), id, actor.userId(), Uuid7.next(), generation, fence);
        if (mapper.workspaceState(actor.spaceId(), id, "PROVISIONING", null, operation, version) != 1) throw new WorkspaceFailure(409, "WORKSPACE_CONCURRENT_CHANGE");
        mapper.insertRequest(actor, key, hash, id, operation);
        mapper.insertOutbox(Uuid7.next(), actor.spaceId(), id, operation, generation, fence);
        row = mapper.workspace(actor.spaceId(), id); event(row); return view(row);
    }
    @Override public List<Publication> publications(UUID owner) {
        requireTransaction(); return mapper.publications(owner).stream().map(row -> new Publication(row.id(), row.operationId(), row.spaceId(),
                row.workspaceId(), row.generation(), row.fence(), row.relayOwner())).toList();
    }
    @Override public void published(Publication item, boolean confirmed) { requireTransaction(); mapper.published(item, confirmed); }
    @Override public Command claim(Publication item, UUID owner) {
        requireTransaction(); mapper.lockCapacity();
        if(!mapper.knownPublication(item)) {
            mapper.quarantine(Uuid7.next(),"UNKNOWN_WORKSPACE_DELIVERY",RunnerSignatures.sha256(JSON.writeValueAsBytes(item)));
            return null;
        }
        var row = locked(item.spaceId(), item.workspaceId());
        var op = mapper.operation(item.operationId());
        if (op == null || !row.operationId().equals(op.id()) || !op.spaceId().equals(item.spaceId())
                || !op.workspaceId().equals(item.workspaceId()) || op.generation() != item.generation() || op.fence() != item.fence()) return null;
        authorize(new Actor(op.actorId(), op.spaceId()));
        if (!op.status().equals("PENDING")) return null; // A committed claim is not stolen after lease expiry.
        mapper.inbox(item.id(), op.id());
        if (mapper.claim(op.id(), owner) != 1) return null;
        op = mapper.operation(op.id());
        return new Command(op.commandId(), op.id(), op.spaceId(), op.workspaceId(), op.actorId(), op.ownerId(),
                op.generation(), op.fence(), "PROVISION", row.quotaBytes(), row.quotaInodes(), row.projectId(), op.deadlineAt());
    }
    private void guard(Command command, boolean authorization) { guard(command, authorization, true); }
    @Override public Command lookup(UUID id) {
        requireTransaction(); mapper.lockCapacity();
        var op = mapper.findOperation(id);
        if(op==null)throw new WorkspaceFailure(404,"RESOURCE_NOT_FOUND");
        var row = locked(op.spaceId(),op.workspaceId());
        op=mapper.operation(id);
        var command = new Command(op.commandId(),op.id(),op.spaceId(),op.workspaceId(),op.actorId(),op.ownerId(),
                op.generation(),op.fence(),"PROVISION",row.quotaBytes(),row.quotaInodes(),row.projectId(),op.deadlineAt());
        readGuard(command);return command;
    }
    private void readGuard(Command command) {
        requireTransaction();mapper.lockCapacity();var row=locked(command.spaceId(),command.workspaceId());var op=mapper.operation(command.operationId());
        boolean terminal=op!=null&&java.util.Set.of("COMMITTED","FAILED","UNKNOWN").contains(op.status());
        if(op==null||!row.operationId().equals(op.id())||!op.commandId().equals(command.commandId())
                ||!op.actorId().equals(command.actorId())||!Objects.equals(op.ownerId(),command.ownerId())
                ||op.generation()!=command.generation()||op.fence()!=command.fence()
                ||row.projectId()!=command.projectId()||row.quotaBytes()!=command.quotaBytes()||row.quotaInodes()!=command.quotaInodes()
                ||!Objects.equals(op.deadlineAt(),command.deadline())||!"PROVISION".equals(command.kind())
                ||!terminal&&!mapper.owned(command))throw new WorkspaceFailure(409,"WORKSPACE_STALE_OWNER");
        authorize(new Actor(command.actorId(),command.spaceId()));
    }
    private void guard(Command command, boolean authorization, boolean active) {
        requireTransaction(); mapper.lockCapacity(); var row = locked(command.spaceId(), command.workspaceId());
        var op = mapper.operation(command.operationId());
        if (!row.operationId().equals(command.operationId()) || op == null || !(active ? mapper.current(command) : mapper.owned(command))
                || !"PROVISION".equals(command.kind()) || row.projectId() != command.projectId()
                || row.quotaBytes() != command.quotaBytes() || row.quotaInodes() != command.quotaInodes()
                || !Objects.equals(op.deadlineAt(), command.deadline())) throw new WorkspaceFailure(409, "WORKSPACE_STALE_OWNER");
        if (authorization) authorize(new Actor(command.actorId(), command.spaceId()));
    }
    @Override public void check(Command command) { guard(command, true, false); }
    @Override public void checkOwnership(Command command) { guard(command, false, false); }
    @Override public void renew(Command command) {
        guard(command, true); if (mapper.renew(command) != 1) throw new WorkspaceFailure(409, "WORKSPACE_STALE_OWNER");
    }
    @Override public void beforeCall(Command command) {
        guard(command, true); if (mapper.beforeCall(command) != 1) throw new WorkspaceFailure(409, "WORKSPACE_STALE_OWNER");
    }
    @Override public Receipt accept(Command command, String requestHash) {
        readGuard(command);
        var old = mapper.runnerCommand(command.commandId());
        if (old != null) {
            if (!old.requestHash().equals(requestHash)) throw new WorkspaceFailure(409, "RUNNER_COMMAND_CONFLICT");
            return receipt(command);
        }
        guard(command,true);
        if (mapper.accept(command, requestHash) != 1) throw new WorkspaceFailure(409, "RUNNER_COMMAND_CONFLICT");
        return null;
    }
    @Override public Receipt receipt(Command command) {
        readGuard(command);
        var row = mapper.receipt(command.commandId());
        if (row == null) return new Receipt(command.commandId(), command.operationId(), command.workspaceId(), command.generation(),
                command.fence(), "RUNNING", "MAY_HAVE_STARTED", null, null, 0);
        if (!RunnerSignatures.sha256(row.resultJson().getBytes(StandardCharsets.UTF_8)).equals(row.contentHash()))
            throw new WorkspaceFailure(409, "RUNNER_RECEIPT_CORRUPT");
        return JSON.readValue(row.resultJson(), Receipt.class);
    }
    private static void validateReceipt(Command command, Receipt receipt) {
        if(receipt==null)throw new WorkspaceFailure(409,"RUNNER_RECEIPT_INVALID");
        if (!command.commandId().equals(receipt.commandId()) || !command.operationId().equals(receipt.operationId())
                || !command.workspaceId().equals(receipt.workspaceId()) || command.generation() != receipt.generation()
                || command.fence() != receipt.fence() || receipt.revision() != 1
                || !("COMMITTED".equals(receipt.state()) && "ALLOCATED".equals(receipt.effectState()) && receipt.allocationHash() != null && receipt.allocationHash().matches("[a-f0-9]{64}") && receipt.errorCode() == null
                || "FAILED".equals(receipt.state()) && "NOT_STARTED".equals(receipt.effectState()) && receipt.allocationHash()==null && receipt.errorCode()!=null && receipt.errorCode().matches("[A-Z_]{1,64}")
                || "UNKNOWN".equals(receipt.state()) && "MAY_HAVE_STARTED".equals(receipt.effectState()) && receipt.allocationHash()==null && receipt.errorCode()!=null && receipt.errorCode().matches("[A-Z_]{1,64}")))
            throw new WorkspaceFailure(409, "RUNNER_RECEIPT_INVALID");
    }
    @Override public void commitReceipt(Command command, Receipt receipt, String json, String hash) {
        guard(command, true); validateReceipt(command, receipt);
        if (!receipt.equals(JSON.readValue(json, Receipt.class)) || !hash.equals(RunnerSignatures.sha256(json.getBytes(StandardCharsets.UTF_8))))
            throw new WorkspaceFailure(409, "RUNNER_RECEIPT_INVALID");
        if (mapper.commitRunner(command, receipt) != 1) throw new WorkspaceFailure(409, "RUNNER_RECEIPT_CONFLICT");
        mapper.insertReceipt(command, receipt, json, hash);
    }
    @Override public void complete(Command command, Receipt receipt) {
        guard(command, true); validateReceipt(command, receipt);
        var stored = mapper.receipt(command.commandId());
        if (stored == null || !receipt.equals(JSON.readValue(stored.resultJson(), Receipt.class))) throw new WorkspaceFailure(409, "RUNNER_RECEIPT_MISSING");
        var row = locked(command.spaceId(), command.workspaceId());
        if (mapper.complete(command, receipt) != 1) throw new WorkspaceFailure(409, "WORKSPACE_STALE_OWNER");
        String state = receipt.state().equals("COMMITTED") ? "READY" : receipt.state().equals("FAILED") ? "ERROR" : "BLOCKED";
        if(mapper.workspaceState(command.spaceId(), command.workspaceId(), state, receipt.errorCode(), command.operationId(), row.version())!=1)
            throw new WorkspaceFailure(409,"WORKSPACE_CONCURRENT_CHANGE");
        mapper.reservationState(command.workspaceId(), state.equals("READY") ? "ALLOCATED" : state.equals("BLOCKED") ? "RECONCILING" : "RESERVED");
        event(mapper.workspace(command.spaceId(), command.workspaceId()));
    }
    @Override public void uncertain(Command command, String code) {
        guard(command, false); var row = locked(command.spaceId(), command.workspaceId());
        if (mapper.uncertain(command, code) != 1) throw new WorkspaceFailure(409, "WORKSPACE_STALE_OWNER");
        mapper.workspaceState(command.spaceId(), command.workspaceId(), "BLOCKED", code, command.operationId(), row.version());
        mapper.reservationState(command.workspaceId(), "RECONCILING"); event(mapper.workspace(command.spaceId(), command.workspaceId()));
    }
    @Override public void quarantine(UUID id, String reason, String hash) { requireTransaction(); mapper.quarantine(id, reason, hash); }
    @Override public int expireUnresolved() {
        requireTransaction();mapper.lockCapacity();var candidate=mapper.findExpired();
        if(candidate==null)return 0;
        var row=locked(candidate.spaceId(),candidate.workspaceId());var operation=mapper.operation(candidate.id());
        if(!row.operationId().equals(operation.id())||mapper.expireUnresolved(operation.id())!=1)return 0;
        if(mapper.workspaceState(row.spaceId(),row.id(),"BLOCKED","WORKSPACE_OWNER_UNCONFIRMED",operation.id(),row.version())!=1)
            throw new WorkspaceFailure(409,"WORKSPACE_CONCURRENT_CHANGE");
        mapper.reservationState(row.id(),"RECONCILING");event(mapper.workspace(row.spaceId(),row.id()));return 1;
    }
}
