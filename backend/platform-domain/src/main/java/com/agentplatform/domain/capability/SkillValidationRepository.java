package com.agentplatform.domain.capability;

import java.util.List;
import java.util.UUID;

/** Fenced validation facts; repository methods execute short transactions only. */
public interface SkillValidationRepository {
    record Command(UUID spaceId, UUID actorId, UUID importId, UUID ownerId, long generation, long fence,
                   String objectKey, long bytes, String sha256) {}
    record Resource(String path, long bytes, String sha256) {}
    record Manifest(String archiveSha256, String name, String description, long expandedBytes, List<Resource> files) {
        public Manifest { files = List.copyOf(files); }
    }
    void guard(Command command);
    void draft(Command command, Manifest manifest);
    void rejected(Command command, String reasonCode);
    void unknown(Command command, String reasonCode);
}
