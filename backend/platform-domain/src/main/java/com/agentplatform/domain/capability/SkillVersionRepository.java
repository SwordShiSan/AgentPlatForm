package com.agentplatform.domain.capability;

import com.agentplatform.domain.capability.CapabilityBindingRepository.Actor;
import java.util.UUID;

/** Immutable Skill metadata plus versioned publication status. */
public interface SkillVersionRepository {
    record View(UUID capabilityId, UUID versionId, String state, long revision, String name, String description,
                String manifestJson, String dependenciesJson, boolean assetAvailable) {}
    record Command(UUID requestId, UUID capabilityId, UUID versionId, String action, long expectedRevision,
                   long resultRevision, String resultState) {}
    boolean authorized(Actor actor);
    void lock(Actor actor);
    View find(Actor actor, UUID capabilityId, UUID versionId, boolean lock);
    Command accepted(Actor actor, UUID requestId);
    boolean transition(Actor actor, UUID capabilityId, UUID versionId, long expected, String from, String to);
    void record(Actor actor, Command command, UUID traceId);
}
