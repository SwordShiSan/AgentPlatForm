package com.agentplatform.domain.capability;

import java.util.UUID;

/** Short, authorized transactions only; the archive and object transport stay outside the database. */
public interface SkillImportRepository {
    record Actor(UUID userId, UUID spaceId) {}
    record Upload(UUID id, UUID capabilityId, UUID versionId, UUID assetId, String state,
                  long size, String sha256, String objectKey, long revision) {}
    Upload create(Actor actor, UUID requestId, String requestHash, UUID existingCapability, long bytes, String sha256);
    Upload find(Actor actor, UUID importId);
    Upload beginTransfer(Actor actor, UUID importId, UUID owner);
    void guardTransfer(Actor actor, UUID importId, UUID owner);
    void verified(Actor actor, UUID importId, UUID owner);
    void transferUnknown(Actor actor, UUID importId, UUID owner);
    Upload enqueue(Actor actor, UUID importId, UUID requestId);
}
