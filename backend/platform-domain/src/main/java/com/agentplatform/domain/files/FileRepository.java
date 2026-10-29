package com.agentplatform.domain.files;

import com.agentplatform.domain.files.FileData.*;
import java.util.List;
import java.util.UUID;

/** High-level short transactions. No transport or filesystem work is performed by a repository. */
public interface FileRepository {
    default java.util.List<FileCaptureData.Target> admitCaptures(FileData.Actor actor,java.util.UUID sourceOperation){throw new FileFailure(422,"CAPTURE_NOT_AVAILABLE");}
    default FileCaptureData.Target captureTarget(FileData.Command command){throw new FileFailure(422,"CAPTURE_NOT_AVAILABLE");}
    default FileCaptureData.Descriptor captureDescriptor(FileData.Command command){throw new FileFailure(422,"CAPTURE_NOT_AVAILABLE");}
    Upload create(Actor actor, UUID workspace, UUID requestId, String requestHash, UploadRequest request);
    Upload upload(Actor actor, UUID id);
    Upload beginTransfer(Actor actor, UUID id, UUID owner);
    void transferVerified(Actor actor, UUID id, UUID owner);
    void transferUncertain(Actor actor, UUID id, UUID owner);
    void transferRejected(Actor actor, UUID id, UUID owner);
    void guardTransfer(Actor actor, UUID id, UUID owner);
    List<Upload> uploadReconciliations(UUID after);
    Upload fenceUploadReconciliation(Upload observed);
    void guardUploadReconciliation(Upload fenced);
    void confirmRecoveredUpload(Upload fenced);
    Operation completeUpload(Actor actor, UUID uploadId);
    List<Entry> list(Actor actor, UUID workspace, String parent, String after, int limit);
    Entry file(Actor actor, UUID fileId);
    Version version(Actor actor, UUID fileId, UUID versionId);
    default List<HistoricalVersion> versions(Actor actor, UUID fileId, UUID before, int limit) {
        throw new FileFailure(503, "FILE_HISTORY_UNAVAILABLE");
    }
    default List<Artifact> artifacts(Actor actor,UUID task,UUID after,int limit) {throw new FileFailure(503,"ARTIFACT_UNAVAILABLE");}
    default Artifact artifact(Actor actor,UUID id) {throw new FileFailure(503,"ARTIFACT_UNAVAILABLE");}
    Usage usage(Actor actor, UUID workspace);
    Operation operation(Actor actor, UUID id);
    default List<FileMutationData.TreeFile> treeFiles(Command command){throw new FileFailure(503,"RESTORE_TREE_UNAVAILABLE");}
    List<Publication> publications(UUID owner);
    void published(Publication item, boolean success);
    Command claim(Publication item, UUID owner);
    void renew(Command command);
    void guard(Command command);
    void beforeCall(Command command);
    Receipt receipt(Command command);
    void commitProjection(Command command, Receipt receipt);
    void uncertain(Command command, String code);
    void blockExpired();
    void quarantine(UUID id, String code, String bodyHash);
    List<Command> reconciliations();
    void observeProjection(Command command);
    void reconcileProjection(Command command, Receipt receipt);
    void scheduleScan();
    Upload claimCleanup();
    void cleanupComplete(Upload upload);
}
