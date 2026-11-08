package com.agentplatform.domain.capability;

import com.agentplatform.domain.capability.CapabilityBindingRepository.Actor;
import java.util.List;
import java.util.UUID;

public interface SkillCatalogRepository {
    record Item(UUID capabilityId, UUID versionId, long versionNumber, String name, String description, String state, long revision) {}
    boolean authorized(Actor actor);
    boolean exists(Actor actor, UUID capabilityId);
    List<Item> skills(Actor actor, UUID after, int limit);
    List<Item> versions(Actor actor, UUID capabilityId, Long beforeVersion, int limit);
}
