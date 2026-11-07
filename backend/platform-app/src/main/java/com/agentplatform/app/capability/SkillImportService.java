package com.agentplatform.app.capability;

import com.agentplatform.app.files.AuthorizedAssetStream;
import com.agentplatform.domain.capability.SkillImportRepository;
import com.agentplatform.domain.capability.SkillImportRepository.Actor;
import com.agentplatform.domain.files.AssetStore;
import com.agentplatform.domain.files.FileFailure;
import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;

/** Streams one bounded archive to private storage; completion only queues validation. */
public final class SkillImportService {
    public static final long MAX_ARCHIVE_BYTES = 20L * 1024 * 1024;
    private final SkillImportRepository repository;
    private final AssetStore assets;

    public SkillImportService(SkillImportRepository repository, AssetStore assets) {
        this.repository = Objects.requireNonNull(repository);
        this.assets = Objects.requireNonNull(assets);
    }

    public SkillImportRepository.Upload transfer(Actor actor, UUID id, long length, InputStream input) {
        if (actor == null || id == null || input == null || length < 0 || length > MAX_ARCHIVE_BYTES)
            throw new FileFailure(400, "INVALID_SKILL_ARCHIVE");
        var upload = repository.find(actor, id);
        if (length != upload.size()) throw new FileFailure(400, "SKILL_ARCHIVE_LENGTH_MISMATCH");
        UUID owner = UUID.randomUUID();
        upload = repository.beginTransfer(actor, id, owner);
        try {
            assets.put(upload.objectKey(), length, upload.sha256(),
                    new AuthorizedAssetStream(input, length, upload.sha256(), () -> repository.guardTransfer(actor, id, owner)));
            repository.verified(actor, id, owner);
        } catch (RuntimeException failure) {
            try {
                // A status code alone does not prove that no bytes reached object storage.
                repository.transferUnknown(actor, id, owner);
            } catch (RuntimeException unresolved) { failure.addSuppressed(unresolved); }
            throw failure;
        }
        return repository.find(actor, id);
    }

    public SkillImportRepository.Upload complete(Actor actor, UUID id) {
        return repository.enqueue(actor, id);
    }
}
