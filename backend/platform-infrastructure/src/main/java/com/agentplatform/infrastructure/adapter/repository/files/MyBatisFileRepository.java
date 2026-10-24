package com.agentplatform.infrastructure.adapter.repository.files;

import com.agentplatform.domain.files.*;
import com.agentplatform.domain.files.FileData.*;
import com.agentplatform.infrastructure.dao.files.FileMapper;
import com.agentplatform.infrastructure.dao.workspace.EnvironmentPreparationMapper;
import com.agentplatform.infrastructure.dao.workspace.WorkspaceMapper;
import com.agentplatform.infrastructure.workspace.RunnerSignatures;
import com.agentplatform.types.Uuid7;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Short database facts only. Lock order storage -> sandbox slot -> workspace -> operation -> upload. */
public final class MyBatisFileRepository implements FileRepository {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final FileMapper files;
    private final WorkspaceMapper workspaces;
    private final EnvironmentPreparationMapper environments;
    public MyBatisFileRepository(FileMapper files, WorkspaceMapper workspaces, EnvironmentPreparationMapper environments) {
        this.files = files; this.workspaces = workspaces; this.environments = environments;
    }
    private static void tx() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("File transaction required");
    }
    private static void one(int count) { if (count != 1) throw new FileFailure(409, "FILE_CONCURRENT_CHANGE"); }
    private void authorized(Actor actor) {
        tx();
        if (!workspaces.authorized(new com.agentplatform.domain.workspace.WorkspaceData.Actor(actor.userId(), actor.spaceId())))
            throw new FileFailure(403, "FILE_AUTHORIZATION_REVOKED");
    }
    private com.agentplatform.infrastructure.dao.po.WorkspaceRows.WorkspaceRow workspace(Actor actor, UUID id, boolean lock) {
        authorized(actor);
        var row = lock ? workspaces.lockWorkspace(actor.spaceId(), id) : workspaces.workspace(actor.spaceId(), id);
        if (row == null) throw new FileFailure(404, "RESOURCE_NOT_FOUND");
        if (!"READY".equals(row.state())) throw new FileFailure(409, "WORKSPACE_STORAGE_NOT_READY");
        return row;
    }
    private void storage(Actor actor) {
        tx();
        var row = files.lockStorage(actor.spaceId());
        if (row == null || !"ACTIVE".equals(row.status())) throw new FileFailure(409, "SPACE_STORAGE_UNAVAILABLE");
    }
    private void onCommit(Runnable check) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void beforeCommit(boolean readOnly) { check.run(); }
        });
    }
    private void versionCheck(Actor actor, UUID workspace, UploadRequest request) {
        var existing = files.path(actor.spaceId(), workspace, request.path());
        if (!request.replacement()) {
            if (existing != null) throw new FileFailure(409, "FILE_NAME_CONFLICT");
        } else if (existing == null || !existing.id().equals(request.fileId()) || !"FILE".equals(existing.kind())
                || !Objects.equals(existing.currentVersionId(), request.baseVersionId()) || existing.revision() != request.expectedRevision())
            throw new FileFailure(412, "FILE_VERSION_CONFLICT");
    }
    @Override public Upload create(Actor actor, UUID workspace, UUID requestId, String requestHash, UploadRequest request) {
        storage(actor);workspace(actor, workspace, true);
        var previous = files.request(actor, requestId);
        if (previous != null) {
            if (!previous.requestHash().equals(requestHash) || !previous.workspaceId().equals(workspace))
                throw new FileFailure(409, "IDEMPOTENCY_CONFLICT");
            return previous;
        }
        versionCheck(actor, workspace, request);
        if (files.reserve(actor.spaceId(), request.bytes()) != 1) throw new FileFailure(409, "FILE_VERSION_QUOTA_EXCEEDED");
        UUID id = Uuid7.next(), version = Uuid7.next(), asset = Uuid7.next();
        String key = "spaces/" + actor.spaceId() + "/file-version/" + asset + "/" + version + "/" + Uuid7.next();
        var row = new Upload(id, actor.spaceId(), workspace, actor.userId(), requestId, requestHash, request.path(),
                request.replacement() ? "REPLACE" : "CREATE", request.replacement() ? request.fileId() : Uuid7.next(),
                request.baseVersionId(), request.expectedRevision(), version, asset, key, request.bytes(), request.sha256(),
                "CREATED", null, null, null, null, 1);
        files.insertAsset(row);files.insertUpload(row);files.insertReservation(row);
        onCommit(() -> authorized(actor));
        return files.upload(id);
    }
    private Upload ownedUpload(Actor actor, UUID id, boolean lock) {
        authorized(actor);
        var row = lock ? files.lockUpload(id) : files.upload(id);
        if (row == null || !row.spaceId().equals(actor.spaceId()) || !row.actorId().equals(actor.userId()))
            throw new FileFailure(404, "RESOURCE_NOT_FOUND");
        workspace(actor, row.workspaceId(), false);
        return row;
    }
    @Override public Upload upload(Actor actor, UUID id) { return ownedUpload(actor, id, false); }
    @Override public Upload beginTransfer(Actor actor, UUID id, UUID owner) {
        var row = ownedUpload(actor, id, true);
        if (!"CREATED".equals(row.status())) throw new FileFailure(409, "UPLOAD_RECONCILIATION_REQUIRED");
        one(files.beginTransfer(id, owner));onCommit(() -> authorized(actor));return files.upload(id);
    }
    @Override public void transferVerified(Actor actor, UUID id, UUID owner) {
        ownedUpload(actor, id, true);one(files.transferVerified(id, owner));onCommit(() -> authorized(actor));
    }
    @Override public void transferUncertain(Actor actor, UUID id, UUID owner) {
        ownedUpload(actor, id, true);one(files.transferUncertain(id, owner));
    }
    @Override public Operation completeUpload(Actor actor, UUID id) {
        // Inspect identity without taking a lower-order upload lock first.
        var before = ownedUpload(actor, id, false);storage(actor);workspace(actor, before.workspaceId(), true);
        var row = ownedUpload(actor, id, true);
        if (row.operationId() != null) return files.operation(row.operationId());
        if (!"VERIFIED".equals(row.status())) throw new FileFailure(409, "UPLOAD_NOT_VERIFIED");
        versionCheck(actor, row.workspaceId(), new UploadRequest(row.relativePath(), row.sizeBytes(), row.sha256(),
                "REPLACE".equals(row.mode()) ? row.fileId() : null, row.baseVersionId(), row.expectedRevision()));
        var operation = new Operation(Uuid7.next(), row.spaceId(), row.workspaceId(), row.actorId(), "APPLY_UPLOAD", row.id(),
                Uuid7.next(), null, 1, null, null, null, "PENDING", "NOT_STARTED", null, 1);
        files.insertOperation(operation);one(files.queueUpload(id, operation.id()));files.insertOutbox(Uuid7.next(), operation);
        onCommit(() -> authorized(actor));return files.operation(operation.id());
    }
    @Override public List<Entry> list(Actor actor, UUID workspace, String parent, String after, int limit) {
        workspace(actor, workspace, false);
        return files.list(actor.spaceId(), workspace, FileRules.path(parent, true), after, Math.max(1, Math.min(limit, 100)));
    }
    @Override public Entry file(Actor actor, UUID id) {
        authorized(actor);var row = files.file(id);
        if (row == null || !row.spaceId().equals(actor.spaceId())) throw new FileFailure(404, "RESOURCE_NOT_FOUND");
        workspace(actor, row.workspaceId(), false);return row;
    }
    @Override public Version version(Actor actor, UUID file, UUID version) {
        var current = file(actor, file);
        var row = files.version(actor.spaceId(), file, version == null ? current.currentVersionId() : version);
        if (row == null) throw new FileFailure(404, "RESOURCE_NOT_FOUND");return row;
    }
    @Override public Usage usage(Actor actor, UUID workspace) {
        workspace(actor, workspace, false);return files.usage(actor.spaceId(), workspace);
    }
    @Override public Operation operation(Actor actor, UUID id) {
        authorized(actor);var row = files.operation(id);
        if (row == null || !row.spaceId().equals(actor.spaceId())) throw new FileFailure(404, "RESOURCE_NOT_FOUND");
        workspace(actor, row.workspaceId(), false);return row;
    }
    @Override public List<Publication> publications(UUID owner) { tx();return files.publications(owner); }
    @Override public void published(Publication item, boolean success) { tx();one(files.published(item, success)); }
    @Override public Command claim(Publication item, UUID owner) {
        tx();
        if (!files.knownPublication(item)) throw new FileFailure(409, "UNKNOWN_FILE_PUBLICATION");
        var observed = files.operation(item.operationId());
        var actor = new Actor(observed.actorId(), observed.spaceId());
        storage(actor);environments.lockSlot();var workspace = workspace(actor, item.workspaceId(), true);
        files.ensureLease(actor.spaceId(), workspace.id());var lease = files.lockLease(actor.spaceId(), workspace.id());
        var operation = files.lockOperation(item.operationId());
        if (!"PENDING".equals(operation.status())) { files.inbox(item);return null; }
        if (!"FREE".equals(lease.status()) || files.unresolvedEnvironmentUse(workspace.id())) throw new FileFailure(423, "WORKSPACE_BUSY");
        var upload = ownedUpload(actor, operation.uploadId(), true);
        versionCheck(actor, workspace.id(), new UploadRequest(upload.relativePath(), upload.sizeBytes(), upload.sha256(),
                "REPLACE".equals(upload.mode()) ? upload.fileId() : null, upload.baseVersionId(), upload.expectedRevision()));
        long fence = Math.addExact(lease.fence(), 1);
        one(files.takeLease(operation, owner, fence));one(files.claim(operation.id(), owner, fence));files.inbox(item);
        var command = new Command(files.operation(operation.id()), files.upload(upload.id()), workspace.projectId());
        onCommit(() -> guard(command));return command;
    }
    @Override public void guard(Command command) {
        tx();var operation = command.operation();authorized(new Actor(operation.actorId(), operation.spaceId()));
        if (!files.current(command)) throw new FileFailure(409, "FILE_STALE_OWNER");
    }
    @Override public void renew(Command command) {
        lockOwned(command);one(files.renew(command));one(files.renewLease(command));onCommit(() -> guard(command));
    }
    private void lockOwned(Command command) {
        var operation = command.operation();storage(new Actor(operation.actorId(), operation.spaceId()));environments.lockSlot();
        workspace(new Actor(operation.actorId(), operation.spaceId()), operation.workspaceId(), true);
        files.lockLease(operation.spaceId(), operation.workspaceId());files.lockOperation(operation.id());guard(command);
    }
    @Override public void beforeCall(Command command) { lockOwned(command);one(files.beforeCall(command));onCommit(() -> guard(command)); }
    @Override public Receipt receipt(Command command) { tx();return files.receipt(command); }
    private record Applied(String protocol, UUID operationId, String state, long bytes, String sha256) {}
    @Override public void commitProjection(Command command, Receipt supplied) {
        lockOwned(command);var stored = files.receipt(command);
        if (stored == null || !stored.equals(supplied) || !"APPLIED".equals(stored.resultState()))
            throw new FileFailure(409, "FILE_RESULT_RECONCILIATION_REQUIRED");
        if (!RunnerSignatures.sha256(stored.resultText().getBytes(StandardCharsets.UTF_8)).equals(stored.resultHash())
                || !RunnerSignatures.sha256(stored.stopText().getBytes(StandardCharsets.UTF_8)).equals(stored.stopHash()))
            throw new FileFailure(503, "FILE_RECEIPT_MISMATCH");
        var result = JSON.readValue(stored.resultText(), Applied.class);
        var upload = ownedUpload(new Actor(command.operation().actorId(), command.operation().spaceId()), command.upload().id(), true);
        if (!"file-stream-v1".equals(result.protocol()) || !result.operationId().equals(command.operation().id())
                || !"APPLIED".equals(result.state()) || result.bytes() != upload.sizeBytes() || !result.sha256().equals(upload.sha256()))
            throw new FileFailure(503, "FILE_RECEIPT_MISMATCH");
        if ("CREATE".equals(upload.mode())) {
            int separator = upload.relativePath().lastIndexOf('/');
            files.insertFile(new Entry(upload.fileId(), upload.spaceId(), upload.workspaceId(), upload.relativePath(),
                    separator < 0 ? "" : upload.relativePath().substring(0, separator), upload.relativePath().substring(separator + 1),
                    "FILE", null, "PENDING", 1, null, null));
        }
        files.insertVersion(upload, command.operation().id());one(files.commitFile(upload));one(files.availableAsset(upload));
        files.reference(upload);one(files.settleReservation(upload));one(files.settleSpace(upload));one(files.commitUpload(upload));
        var entry = files.file(upload.fileId());
        files.event(Uuid7.next(), upload, entry.revision(), JSON.writeValueAsString(java.util.Map.of(
                "workspaceId",upload.workspaceId(),"fileId",upload.fileId(),"versionId",upload.versionId(),"revision",entry.revision())));
        // Check the old active ownership immediately before its intentional terminal transition.
        guard(command);one(files.completeOperation(command));one(files.releaseLease(command));
        onCommit(() -> {
            authorized(new Actor(command.operation().actorId(), command.operation().spaceId()));
            if (!files.committedOwned(command)) throw new FileFailure(409, "FILE_STALE_OWNER");
        });
    }
    @Override public void uncertain(Command command, String code) {
        lockOwned(command);one(files.uncertain(command, code));one(files.blockLease(command));
    }
}
