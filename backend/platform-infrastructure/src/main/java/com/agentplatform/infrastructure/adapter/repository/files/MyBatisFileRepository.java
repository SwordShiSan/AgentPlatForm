package com.agentplatform.infrastructure.adapter.repository.files;

import com.agentplatform.domain.files.*;
import com.agentplatform.domain.files.FileData.*;
import com.agentplatform.infrastructure.dao.files.FileMapper;
import com.agentplatform.infrastructure.dao.files.FileCaptureMapper;
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
    private final String helperImageId, helperPolicyHash;
    private final FileCaptureMapper captures;
    public MyBatisFileRepository(FileMapper files, WorkspaceMapper workspaces, EnvironmentPreparationMapper environments,
            String helperImageId,String helperPolicyHash) {
        this(files,workspaces,environments,helperImageId,helperPolicyHash,null);
    }
    public MyBatisFileRepository(FileMapper files, WorkspaceMapper workspaces, EnvironmentPreparationMapper environments,
            String helperImageId,String helperPolicyHash,FileCaptureMapper captures) {
        this.files = files; this.workspaces = workspaces; this.environments = environments;
        this.captures=captures;
        if(helperImageId==null||!helperImageId.matches("sha256:[a-f0-9]{64}")||helperPolicyHash==null||!helperPolicyHash.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Frozen file helper policy required");
        this.helperImageId=helperImageId;this.helperPolicyHash=helperPolicyHash;
    }
    @Override public List<FileCaptureData.Target> admitCaptures(Actor actor,UUID sourceOperation) {
        tx();if(captures==null)throw new FileFailure(422,"CAPTURE_NOT_AVAILABLE");
        UUID source=captures.sourceUse(actor,sourceOperation);
        if(source==null||!captures.parentAllowed(source,true))throw new FileFailure(409,"SHELL_STOP_NOT_COMMITTED");
        var rows=captures.reservations(actor,sourceOperation,false);if(rows.isEmpty())return List.of();
        UUID workspaceId=rows.getFirst().workspaceId();storage(actor);environments.lockSlot();workspace(actor,workspaceId,true);
        files.ensureLease(actor.spaceId(),workspaceId);var lease=files.lockLease(actor.spaceId(),workspaceId);
        if(!"FREE".equals(lease.status()))throw new FileFailure(423,"WORKSPACE_BUSY");
        rows=captures.reservations(actor,sourceOperation,true);
        var result=new java.util.ArrayList<FileCaptureData.Target>();
        for(var row:rows) {
            UUID operation=row.fileOperationId();
            if(operation==null) {
                operation=Uuid7.next();var job=new Operation(operation,actor.spaceId(),workspaceId,actor.userId(),"CAPTURE",null,Uuid7.next(),null,1,null,null,null,
                    "PENDING","NOT_STARTED",null,1,helperImageId,helperPolicyHash,null,null,null,null);
                files.insertOperation(job);one(captures.bind(row.id(),operation));one(captures.bindSource(row.id(),Uuid7.next()));files.insertOutbox(Uuid7.next(),job);
            }
            result.add(new FileCaptureData.Target(row.id(),row.operationId(),row.spaceId(),row.workspaceId(),operation,row.path(),row.bytes(),row.versionId(),row.assetId(),row.objectKey()));
        }
        onCommit(()->{if(!captures.parentAllowed(source,false))throw new FileFailure(409,"TOOL_PARENT_NO_LONGER_AUTHORIZED");});
        return List.copyOf(result);
    }
    @Override public FileCaptureData.Target captureTarget(Command command) {
        tx();if(captures==null||!"CAPTURE".equals(command.operation().kind()))throw new FileFailure(422,"CAPTURE_NOT_AVAILABLE");
        var row=files.operation(command.operation().id());
        if(row==null||!Execution.freeze(row).equals(command.operation()))throw new FileFailure(409,"FILE_STALE_OWNER");
        var target=captures.target(command);if(target==null)throw new FileFailure(404,"RESOURCE_NOT_FOUND");return target;
    }
    @Override public FileCaptureData.Descriptor captureDescriptor(Command command) {
        captureTarget(command);var descriptor=captures.descriptor(command);if(descriptor==null)throw new FileFailure(409,"CAPTURE_DESCRIPTOR_PENDING");return descriptor;
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
        if (lock && environments.lifecycleBusy(actor.spaceId(),id)) throw new FileFailure(423,"ENVIRONMENT_LIFECYCLE_BUSY");
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
        if(lock&&!files.toolParentAllowed(id,null,true))throw new FileFailure(409,"TOOL_PARENT_NO_LONGER_AUTHORIZED");
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
    @Override public void transferRejected(Actor actor,UUID id,UUID owner) {
        ownedUpload(actor,id,true);one(files.transferRejected(id,owner));onCommit(()->authorized(actor));
    }
    @Override public void guardTransfer(Actor actor,UUID id,UUID owner) {
        if(!files.toolParentAllowed(id,null,false))throw new FileFailure(409,"TOOL_PARENT_NO_LONGER_AUTHORIZED");
        var row=ownedUpload(actor,id,false);var now=files.databaseTime();
        if(!"UPLOADING".equals(row.status())||!owner.equals(row.transferOwner())||row.transferUntil()==null
                ||!row.transferUntil().isAfter(now)||!row.expiresAt().isAfter(now))throw new FileFailure(409,"UPLOAD_TRANSFER_OWNER_EXPIRED");
    }
    @Override public List<Upload> uploadReconciliations(UUID after){tx();return files.uploadReconciliations(after);}
    @Override public Upload fenceUploadReconciliation(Upload observed) {
        var actor=new Actor(observed.actorId(),observed.spaceId());storage(actor);workspace(actor,observed.workspaceId(),true);
        var current=ownedUpload(actor,observed.id(),true);
        if(current.revision()!=observed.revision()||!Input.freeze(current).equals(Input.freeze(observed))||current.operationId()!=null)
            throw new FileFailure(409,"UPLOAD_RECONCILIATION_CHANGED");
        if("UPLOADING".equals(current.status())) {
            // This only fences metadata. It neither releases the transport nor proves a stop.
            one(files.fenceExpiredUpload(current.id(),current.revision()));current=files.upload(current.id());
        }
        var fenced=current;guardUploadReconciliation(fenced);onCommit(()->guardUploadReconciliation(fenced));return fenced;
    }
    @Override public void guardUploadReconciliation(Upload fenced) {
        var current=ownedUpload(new Actor(fenced.actorId(),fenced.spaceId()),fenced.id(),false);
        if(!"VERIFYING".equals(current.status())||current.revision()!=fenced.revision()||current.transferOwner()!=null
                ||current.transferUntil()!=null||current.operationId()!=null||!Input.freeze(current).equals(Input.freeze(fenced)))
            throw new FileFailure(409,"UPLOAD_RECONCILIATION_CHANGED");
    }
    @Override public void confirmRecoveredUpload(Upload fenced) {
        var actor=new Actor(fenced.actorId(),fenced.spaceId());storage(actor);
        if(!files.lockTransferIdle())throw new FileFailure(423,"FILE_TRANSFER_BUSY");
        workspace(actor,fenced.workspaceId(),true);
        ownedUpload(actor,fenced.id(),true);guardUploadReconciliation(fenced);
        one(files.recoverVerifiedUpload(fenced.id(),fenced.revision()));onCommit(()->authorized(actor));
    }
    @Override public Operation completeUpload(Actor actor, UUID id) {
        if(!files.toolParentAllowed(id,null,true))throw new FileFailure(409,"TOOL_PARENT_NO_LONGER_AUTHORIZED");
        // Inspect identity without taking a lower-order upload lock first.
        var before = ownedUpload(actor, id, false);storage(actor);workspace(actor, before.workspaceId(), true);
        var row = ownedUpload(actor, id, true);
        if (row.operationId() != null) return files.operation(row.operationId());
        if (!"VERIFIED".equals(row.status())) throw new FileFailure(409, "UPLOAD_NOT_VERIFIED");
        if(!row.expiresAt().isAfter(files.databaseTime()))throw new FileFailure(409,"UPLOAD_EXPIRED");
        versionCheck(actor, row.workspaceId(), new UploadRequest(row.relativePath(), row.sizeBytes(), row.sha256(),
                "REPLACE".equals(row.mode()) ? row.fileId() : null, row.baseVersionId(), row.expectedRevision()));
        var operation = new Operation(Uuid7.next(), row.spaceId(), row.workspaceId(), row.actorId(), "APPLY_UPLOAD", row.id(),
                Uuid7.next(), null, 1, null, null, null, "PENDING", "NOT_STARTED", null, 1,helperImageId,helperPolicyHash,null,null,null,null);
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
        if(!files.toolParentAllowed(null,item.operationId(),true))throw new FileFailure(409,"TOOL_PARENT_NO_LONGER_AUTHORIZED");
        tx();
        if (!files.knownPublication(item)) {files.quarantine(Uuid7.next(),"UNKNOWN_FILE_PUBLICATION",RunnerSignatures.sha256(JSON.writeValueAsBytes(item)));return null;}
        var observed = files.operation(item.operationId());
        var actor = new Actor(observed.actorId(), observed.spaceId());
        storage(actor);environments.lockSlot();var workspace = workspace(actor, item.workspaceId(), true);
        files.ensureLease(actor.spaceId(), workspace.id());var lease = files.lockLease(actor.spaceId(), workspace.id());
        var operation = files.lockOperation(item.operationId());
        if (!"PENDING".equals(operation.status())) { files.inbox(item);return null; }
        boolean capture="CAPTURE".equals(operation.kind());
        if (!"FREE".equals(lease.status()) || files.unresolvedEnvironmentUse(workspace.id())&&!(capture&&captures!=null&&captures.mayEnter(operation.id()))) throw new FileFailure(423, "WORKSPACE_BUSY");
        if("SCAN".equals(operation.kind())||capture) {
            if(capture&&(captures==null||!captures.mayEnter(operation.id())||captures.claimed(operation.id())!=1))throw new FileFailure(409,"CAPTURE_NOT_AUTHORIZED");
            long fence=Math.addExact(lease.fence(),1);one(files.takeLease(operation,owner,fence));one(files.claim(operation.id(),owner,fence));files.inbox(item);
            var command=new Command(Execution.freeze(files.operation(operation.id())),null,workspace.projectId(),null);onCommit(()->guard(command));return command;
        }
        var upload = ownedUpload(actor, operation.uploadId(), true);
        try {
            versionCheck(actor, workspace.id(), new UploadRequest(upload.relativePath(), upload.sizeBytes(), upload.sha256(),
                    "REPLACE".equals(upload.mode()) ? upload.fileId() : null, upload.baseVersionId(), upload.expectedRevision()));
        } catch(FileFailure conflict) {
            if(conflict.status()!=409&&conflict.status()!=412)throw conflict;
            // No lease or physical command exists at this boundary; a known conflict is terminal, not poison redelivery.
            one(files.rejectPending(operation.id(),conflict.getMessage()));refund(upload);files.inbox(item);
            onCommit(()->authorized(actor));return null;
        }
        long fence = Math.addExact(lease.fence(), 1);
        one(files.takeLease(operation, owner, fence));one(files.claim(operation.id(), owner, fence));files.inbox(item);
        var base=upload.baseVersionId()==null?null:files.version(upload.spaceId(),upload.fileId(),upload.baseVersionId());
        var command = new Command(Execution.freeze(files.operation(operation.id())), Input.freeze(files.upload(upload.id())), workspace.projectId(),base==null?null:base.sha256());
        onCommit(() -> guard(command));return command;
    }
    @Override public void guard(Command command) {
        if(!files.toolParentAllowed(command.operation().uploadId(),command.operation().id(),false))throw new FileFailure(409,"TOOL_PARENT_NO_LONGER_AUTHORIZED");
        tx();var operation = command.operation();authorized(new Actor(operation.actorId(), operation.spaceId()));
        if (!files.current(command)) throw new FileFailure(409, "FILE_STALE_OWNER");
    }
    @Override public void renew(Command command) {
        lockOwned(command);one(files.renew(command));one(files.renewLease(command));onCommit(() -> guard(command));
    }
    private void lockOwned(Command command) {
        if(!files.toolParentAllowed(command.operation().uploadId(),command.operation().id(),true))throw new FileFailure(409,"TOOL_PARENT_NO_LONGER_AUTHORIZED");
        var operation = command.operation();storage(new Actor(operation.actorId(), operation.spaceId()));environments.lockSlot();
        workspace(new Actor(operation.actorId(), operation.spaceId()), operation.workspaceId(), true);
        files.lockLease(operation.spaceId(), operation.workspaceId());files.lockOperation(operation.id());guard(command);
    }
    @Override public void beforeCall(Command command) { lockOwned(command);one(files.beforeCall(command));onCommit(() -> guard(command)); }
    @Override public Receipt receipt(Command command) { tx();return files.receipt(command); }
    private record Applied(String protocol, UUID operationId, String state, long bytes, String sha256) {}
    @Override public void commitProjection(Command command, Receipt supplied) {
        commitProjection(command,supplied,false);
    }
    @Override public void reconcileProjection(Command command,Receipt supplied){commitProjection(command,supplied,true);}
    @Override public void observeProjection(Command command) {
        var operation=command.operation();storage(new Actor(operation.actorId(),operation.spaceId()));environments.lockSlot();
        workspace(new Actor(operation.actorId(),operation.spaceId()),operation.workspaceId(),true);
        files.lockLease(operation.spaceId(),operation.workspaceId());var current=files.lockOperation(operation.id());
        if(current==null||!Execution.freeze(current).equals(operation)||!files.projectionCurrent(command))throw new FileFailure(409,"FILE_STALE_PROJECTION");
    }
    private void projectionGuard(Command command,boolean recovery){if(recovery)observeProjection(command);else guard(command);}
    private void commitProjection(Command command,Receipt supplied,boolean recovery) {
        if(recovery)observeProjection(command);else lockOwned(command);
        var stored = files.receipt(command);
        if (stored == null || !stored.equals(supplied))
            throw new FileFailure(409, "FILE_RESULT_RECONCILIATION_REQUIRED");
        if (!RunnerSignatures.sha256(stored.resultText().getBytes(StandardCharsets.UTF_8)).equals(stored.resultHash())
                || !RunnerSignatures.sha256(stored.stopText().getBytes(StandardCharsets.UTF_8)).equals(stored.stopHash()))
            throw new FileFailure(503, "FILE_RECEIPT_MISMATCH");
        if("SCAN".equals(command.operation().kind())) {
            commitScan(command,stored,recovery);return;
        }
        if("CAPTURE".equals(command.operation().kind())) {commitCapture(command,stored,recovery);return;}
        var upload = ownedUpload(new Actor(command.operation().actorId(), command.operation().spaceId()), command.upload().id(), true);
        if("UNKNOWN".equals(stored.resultState())) {
            if(recovery)return; // No new write, release or replay is justified by an unknown physical result.
            uncertain(command,"FILE_RESULT_UNKNOWN");return;
        }
        if("NOT_APPLIED".equals(stored.resultState())) {
            // The runner repository accepts this receipt only with positive exact stop facts.
            projectionGuard(command,recovery);refund(upload);one(files.failOperation(command));one(files.releaseLease(command));
            onCommit(()->{
                authorized(new Actor(command.operation().actorId(),command.operation().spaceId()));
                if(!files.toolParentAllowed(command.operation().uploadId(),command.operation().id(),false))throw new FileFailure(409,"TOOL_PARENT_NO_LONGER_AUTHORIZED");
                if(!files.committedOwned(command,recovery))throw new FileFailure(409,"FILE_STALE_OWNER");
            });return;
        }
        if(!"APPLIED".equals(stored.resultState()))throw new FileFailure(409,"FILE_RESULT_RECONCILIATION_REQUIRED");
        var result = JSON.readValue(stored.resultText(), Applied.class);
        if (!("file-stream-v1".equals(result.protocol()) || "file-stream-v2".equals(result.protocol())) || !result.operationId().equals(command.operation().id())
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
        projectionGuard(command,recovery);one(files.completeOperation(command));one(files.releaseLease(command));
        onCommit(() -> {
            authorized(new Actor(command.operation().actorId(), command.operation().spaceId()));
            if(!files.toolParentAllowed(command.operation().uploadId(),command.operation().id(),false))throw new FileFailure(409,"TOOL_PARENT_NO_LONGER_AUTHORIZED");
            if (!files.committedOwned(command,recovery)) throw new FileFailure(409, "FILE_STALE_OWNER");
        });
    }
    @Override public void uncertain(Command command, String code) {
        // Blocking a precisely owned child grants no execution or quota release.
        // It must remain possible after its parent is cancelled/revoked; the
        // ordinary active-parent guard would prevent safe convergence here.
        tx();var operation=command.operation();storage(new Actor(operation.actorId(),operation.spaceId()));environments.lockSlot();
        if(workspaces.lockWorkspace(operation.spaceId(),operation.workspaceId())==null)throw new FileFailure(409,"FILE_STALE_OWNER");
        files.lockLease(operation.spaceId(),operation.workspaceId());files.lockOperation(operation.id());
        if(!files.current(command))throw new FileFailure(409,"FILE_STALE_OWNER");
        one(files.uncertain(command, code));one(files.blockLease(command));if(command.upload()!=null)files.reconcileReservation(command.upload().id());
        if("CAPTURE".equals(operation.kind())&&captures!=null)captures.reconciling(operation.id());
    }
    private void commitCapture(Command command,Receipt receipt,boolean recovery) {
        var target=captureTarget(command);var actor=new Actor(command.operation().actorId(),command.operation().spaceId());
        if("UNKNOWN".equals(receipt.resultState())){if(!recovery)uncertain(command,"FILE_CAPTURE_UNKNOWN");return;}
        if("NOT_APPLIED".equals(receipt.resultState())) {
            if(captures.descriptor(command)!=null){if(!recovery)uncertain(command,"FILE_CAPTURE_UNKNOWN");return;}
            projectionGuard(command,recovery);one(captures.refund(command));one(captures.failed(command));one(files.failOperation(command));one(files.releaseLease(command));
        }else {
            if(!"CAPTURED".equals(receipt.resultState()))throw new FileFailure(409,"FILE_CAPTURE_RECONCILIATION_REQUIRED");
            var descriptor=captureDescriptor(command);var value=JSON.readTree(receipt.resultText());
            if(!"file-capture-v1".equals(value.path("protocol").asString())||!"CAPTURED".equals(value.path("state").asString())
                ||!command.operation().id().toString().equals(value.path("operationId").asString())||descriptor.bytes()!=value.path("bytes").asLong(-1)
                ||!descriptor.sha256().equals(value.path("sha256").asString())||!captures.stoppedMatches(command,receipt.stopHash())||!captures.assetMatches(command))
                throw new FileFailure(503,"FILE_RECEIPT_MISMATCH");
            var existing=files.path(target.spaceId(),target.workspaceId(),target.path());UUID file=existing==null?Uuid7.next():existing.id();
            if(existing==null){int split=target.path().lastIndexOf('/');files.insertFile(new Entry(file,target.spaceId(),target.workspaceId(),target.path(),
                split<0?"":target.path().substring(0,split),target.path().substring(split+1),"FILE",null,"PENDING",1,null,null));}
            else if("DIRECTORY".equals(existing.kind()))captures.markDescendantsMissing(target.spaceId(),target.workspaceId(),target.path());
            var row=new com.agentplatform.infrastructure.dao.po.FileCaptureRows.Projection(command.operation().id(),target.spaceId(),target.workspaceId(),actor.userId(),file,
                existing==null?null:existing.currentVersionId(),target.versionId(),target.assetId(),descriptor.bytes(),descriptor.sha256());
            one(captures.insertVersion(row));one(captures.available(row));one(captures.publishEntry(row));one(captures.reference(row));one(captures.settle(command,descriptor.bytes()));one(captures.complete(row));
            var entry=files.file(file);captures.event(row,Uuid7.next(),entry.revision(),JSON.writeValueAsString(java.util.Map.of("workspaceId",target.workspaceId(),"fileId",file,"versionId",target.versionId(),"revision",entry.revision())));
            projectionGuard(command,recovery);one(files.completeOperation(command));one(files.releaseLease(command));
        }
        onCommit(()->{authorized(actor);if(!files.toolParentAllowed(null,command.operation().id(),false)||!files.committedOwned(command,recovery))throw new FileFailure(409,"FILE_STALE_OWNER");});
    }
    private void refund(Upload upload) {
        one(files.abortUpload(upload));one(files.releaseReservation(upload));one(files.refundSpace(upload));
    }
    @Override public List<Command> reconciliations() {
        tx();return files.reconciliationIds().stream().map(id->{
            var operation=files.operation(id);var workspace=workspaces.workspace(operation.spaceId(),operation.workspaceId());
            var upload=operation.uploadId()==null?null:files.upload(operation.uploadId());
            var base=upload==null||upload.baseVersionId()==null?null:files.version(operation.spaceId(),upload.fileId(),upload.baseVersionId());
            return new Command(Execution.freeze(operation),upload==null?null:Input.freeze(upload),workspace.projectId(),base==null?null:base.sha256());
        }).toList();
    }
    @Override public void scheduleScan() {
        tx();var candidate=files.scanCandidate();if(candidate==null)return;
        var actor=new Actor(candidate.actorId(),candidate.spaceId());storage(actor);environments.lockSlot();
        workspace(actor,candidate.workspaceId(),true);files.ensureLease(actor.spaceId(),candidate.workspaceId());
        var lease=files.lockLease(actor.spaceId(),candidate.workspaceId());
        if(!"FREE".equals(lease.status())||files.unresolvedFileOperation(candidate.workspaceId())||files.unresolvedEnvironmentUse(candidate.workspaceId()))return;
        files.ensureScanDirectory(actor.spaceId(),candidate.workspaceId(),candidate.parentPath(),Uuid7.next());
        var directory=files.lockScanDirectory(actor.spaceId(),candidate.workspaceId(),candidate.parentPath());
        var operation=new Operation(Uuid7.next(),actor.spaceId(),candidate.workspaceId(),actor.userId(),"SCAN",null,Uuid7.next(),null,1,null,null,null,
                "PENDING","NOT_STARTED",null,1,helperImageId,helperPolicyHash,directory.parentPath(),directory.cursor(),directory.epoch(),directory.stamp());
        files.insertOperation(operation);files.insertOutbox(Uuid7.next(),operation);onCommit(()->authorized(actor));
    }
    private void commitScan(Command command,Receipt receipt,boolean recovery) {
        if("UNKNOWN".equals(receipt.resultState())){if(!recovery)uncertain(command,"FILE_SCAN_UNKNOWN");return;}
        if("NOT_APPLIED".equals(receipt.resultState())) {
            files.lockScanDirectory(command.operation().spaceId(),command.operation().workspaceId(),command.operation().scanParent());
            one(files.deferScan(command,Uuid7.next()));projectionGuard(command,recovery);one(files.failOperation(command));one(files.releaseLease(command));
            onCommit(()->{
                authorized(new Actor(command.operation().actorId(),command.operation().spaceId()));
                if(!files.committedOwned(command,recovery))throw new FileFailure(409,"FILE_STALE_OWNER");
            });return;
        }
        if(!"SCANNED".equals(receipt.resultState()))throw new FileFailure(409,"FILE_SCAN_RECONCILIATION_REQUIRED");
        var page=JSON.readValue(receipt.resultText(),ScanPage.class);var operation=command.operation();
        if(!"file-scan-v1".equals(page.protocol())||!"SCANNED".equals(page.state())||!operation.id().equals(page.operationId())
                ||!Objects.equals(operation.scanParent(),page.parent())||!Objects.equals(operation.scanCursor(),page.cursor())
                ||!operation.scanEpoch().equals(page.epoch())||page.entries()==null||page.entries().size()>100||page.stamp()==null
                ||page.restart()&&(!page.entries().isEmpty()||page.next()!=null))throw new FileFailure(503,"FILE_RECEIPT_MISMATCH");
        files.lockScanDirectory(operation.spaceId(),operation.workspaceId(),operation.scanParent());
        String previous=operation.scanCursor();
        for(var entry:page.entries()) {
            String path=FileRules.path(entry.path(),false);int separator=path.lastIndexOf('/');
            String parent=separator<0?"":path.substring(0,separator);
            if(!path.equals(entry.path())||!parent.equals(operation.scanParent())||previous!=null&&java.util.Arrays.compareUnsigned(previous.getBytes(StandardCharsets.UTF_8),path.getBytes(StandardCharsets.UTF_8))>=0
                    ||!java.util.Set.of("FILE","DIRECTORY").contains(entry.kind())||entry.bytes()!=null&&entry.bytes()<0)
                throw new FileFailure(503,"FILE_RECEIPT_MISMATCH");
            if(entry.sha256()!=null)FileRules.sha256(entry.sha256());
            files.scanEntry(Uuid7.next(),command,entry);previous=path;
            if("DIRECTORY".equals(entry.kind()))files.ensureScanDirectory(operation.spaceId(),operation.workspaceId(),path,Uuid7.next());
        }
        if(page.next()!=null&&(page.entries().isEmpty()||!page.next().equals(previous)))throw new FileFailure(503,"FILE_RECEIPT_MISMATCH");
        if(!page.restart()&&page.next()==null)files.missingScanEntries(command);
        one(files.advanceScan(command,page,page.restart()||page.next()==null?Uuid7.next():operation.scanEpoch()));
        projectionGuard(command,recovery);one(files.completeOperation(command));one(files.releaseLease(command));
        onCommit(()->{authorized(new Actor(operation.actorId(),operation.spaceId()));if(!files.committedOwned(command,recovery))throw new FileFailure(409,"FILE_STALE_OWNER");});
    }
    @Override public void quarantine(UUID id,String code,String hash){tx();files.quarantine(id,code,hash);}
    private Upload lockCleanup(UUID id) {
        tx();var observed=files.upload(id);if(observed==null)return null;
        storage(new Actor(observed.actorId(),observed.spaceId()));environments.lockSlot();
        workspaces.lockWorkspace(observed.spaceId(),observed.workspaceId());
        if(observed.operationId()!=null)files.lockOperation(observed.operationId());
        var current=files.lockUpload(id);return files.cleanupEligible(id)?current:null;
    }
    @Override public Upload claimCleanup() {
        tx();var id=files.cleanupCandidate();if(id==null)return null;var upload=lockCleanup(id);if(upload==null)return null;
        one(files.tombstoneUpload(id));one(files.tombstoneAsset(upload));return files.upload(id);
    }
    @Override public void cleanupComplete(Upload candidate) {
        var upload=lockCleanup(candidate.id());
        if(upload==null){var current=files.upload(candidate.id());if(current!=null&&"EXPIRED".equals(current.status()))return;throw new FileFailure(409,"UPLOAD_CLEANUP_CONFLICT");}
        if(!upload.assetId().equals(candidate.assetId())||!upload.objectKey().equals(candidate.objectKey())||!"ABORTED".equals(upload.status()))
            throw new FileFailure(409,"UPLOAD_CLEANUP_CONFLICT");
        one(files.purgeAsset(upload));one(files.expireUpload(upload));
        if(files.releaseReservation(upload)==1)one(files.refundSpace(upload));
    }
    @Override public void blockExpired() {
        tx();
        // One deterministic operation at a time; never take a lower-order row before its storage/slot/workspace.
        for(var id:files.expiredOperations()) {
            var observed=files.operation(id);var actor=new Actor(observed.actorId(),observed.spaceId());
            storage(actor);environments.lockSlot();workspaces.lockWorkspace(actor.spaceId(),observed.workspaceId());
            files.lockLease(actor.spaceId(),observed.workspaceId());files.lockOperation(id);
            if(files.expireOperation(id)==1){files.expireLease(id);if(observed.uploadId()!=null)files.reconcileReservation(observed.uploadId());}
            // The lease, object bytes and reservation remain occupied. Expiry is not a stop receipt.
        }
    }
}
