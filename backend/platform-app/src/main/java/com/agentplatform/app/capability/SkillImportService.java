package com.agentplatform.app.capability;

import com.agentplatform.app.files.AuthorizedAssetStream;
import com.agentplatform.domain.capability.SkillImportRepository;
import com.agentplatform.domain.capability.SkillImportRepository.Actor;
import com.agentplatform.domain.files.AssetStore;
import com.agentplatform.domain.files.FileFailure;
import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Streams one bounded archive to private storage; completion only queues validation. */
public final class SkillImportService implements com.agentplatform.api.capability.SkillImportApi {
    public static final long MAX_ARCHIVE_BYTES = 20L * 1024 * 1024;
    private final SkillImportRepository repository;
    private final AssetStore assets;

    public SkillImportService(SkillImportRepository repository, AssetStore assets) {
        this.repository = Objects.requireNonNull(repository);
        this.assets = Objects.requireNonNull(assets);
    }

    public SkillImportRepository.Upload create(Actor actor, UUID requestId, UUID existingCapability, long bytes, String sha256) {
        if (actor == null || requestId == null || bytes < 0 || bytes > MAX_ARCHIVE_BYTES
                || sha256 == null || !sha256.matches("[a-f0-9]{64}")) throw new FileFailure(400, "INVALID_SKILL_ARCHIVE");
        String content = "skill-import-v1\n" + (existingCapability == null ? "new" : existingCapability)
                + "\n" + bytes + "\n" + sha256 + "\n";
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
            return repository.create(actor, requestId, hash, existingCapability, bytes, sha256);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
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

    public SkillImportRepository.Upload complete(Actor actor, UUID id, UUID requestId) {
        return repository.enqueue(actor, id, requestId);
    }

    @Override public View create(com.agentplatform.api.chat.ChatApi.Caller caller, String requestId, Create body) {
        if (body == null) throw new FileFailure(400, "INVALID_SKILL_ARCHIVE");
        return view(create(actor(caller), uuid(requestId), body.capabilityId() == null ? null : uuid(body.capabilityId()),
                body.sizeBytes(), body.sha256()));
    }
    @Override public View get(com.agentplatform.api.chat.ChatApi.Caller caller, String id) {
        return view(repository.find(actor(caller), uuid(id)));
    }
    @Override public View transfer(com.agentplatform.api.chat.ChatApi.Caller caller, String id, long length, InputStream stream) {
        return view(transfer(actor(caller), uuid(id), length, stream));
    }
    @Override public View complete(com.agentplatform.api.chat.ChatApi.Caller caller, String id, String requestId) {
        return view(complete(actor(caller), uuid(id), uuid(requestId)));
    }
    private static View view(SkillImportRepository.Upload row) {
        return new View(row.id().toString(), row.capabilityId().toString(), row.versionId().toString(), row.state(),
                row.size(), row.sha256(), row.revision());
    }
    private static Actor actor(com.agentplatform.api.chat.ChatApi.Caller caller) {
        if (caller == null) throw new FileFailure(401, "AUTHENTICATION_REQUIRED");
        return new Actor(uuid(caller.userId()), uuid(caller.spaceId()));
    }
    private static UUID uuid(String value) {
        try { return UUID.fromString(value); } catch (RuntimeException invalid) { throw new FileFailure(400, "INVALID_UUID"); }
    }
}
