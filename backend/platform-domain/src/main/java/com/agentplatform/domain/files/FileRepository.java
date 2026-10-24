package com.agentplatform.domain.files;

import com.agentplatform.domain.files.FileData.*;
import java.util.List;
import java.util.UUID;

/** High-level short transactions. No transport or filesystem work is performed by a repository. */
public interface FileRepository {
    Upload create(Actor actor, UUID workspace, UUID requestId, String requestHash, UploadRequest request);
    Upload upload(Actor actor, UUID id);
    Upload beginTransfer(Actor actor, UUID id, UUID owner);
    void transferVerified(Actor actor, UUID id, UUID owner);
    void transferUncertain(Actor actor, UUID id, UUID owner);
    Operation completeUpload(Actor actor, UUID uploadId);
    List<Entry> list(Actor actor, UUID workspace, String parent, String after, int limit);
    Entry file(Actor actor, UUID fileId);
    Version version(Actor actor, UUID fileId, UUID versionId);
    Usage usage(Actor actor, UUID workspace);
    Operation operation(Actor actor, UUID id);
    List<Publication> publications(UUID owner);
    void published(Publication item, boolean success);
    Command claim(Publication item, UUID owner);
    void renew(Command command);
    void guard(Command command);
    void beforeCall(Command command);
    Receipt receipt(Command command);
    void commitProjection(Command command, Receipt receipt);
    void uncertain(Command command, String code);
}
