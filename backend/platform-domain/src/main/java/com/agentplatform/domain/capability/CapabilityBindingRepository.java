package com.agentplatform.domain.capability;

import java.util.List;
import java.util.UUID;

/** Persistence port; callers own the short transaction and perform real-time authorization. */
public interface CapabilityBindingRepository {
    record Actor(UUID userId, UUID spaceId) {}
    record BindingSet(UUID id, long revision, boolean inheritPersonal,
                      List<CapabilityBindings.Selection> selections) {
        public BindingSet { selections = List.copyOf(selections); }
    }
    record AcceptedRequest(String requestHash, BindingSet result) {}

    boolean authorized(Actor actor, UUID workspaceId);
    void lock(Actor actor, UUID workspaceId);
    BindingSet find(Actor actor, UUID workspaceId);
    void initialize(Actor actor, UUID workspaceId, UUID id);
    boolean replace(Actor actor, UUID setId, long expectedRevision, boolean inheritPersonal,
                    List<CapabilityBindings.Selection> selections);
    boolean published(Actor actor, CapabilityBindings.Selection selection);
    AcceptedRequest accepted(Actor actor, UUID requestId);
    void recordAccepted(Actor actor, UUID requestId, String requestHash, BindingSet result, UUID traceId);
}
