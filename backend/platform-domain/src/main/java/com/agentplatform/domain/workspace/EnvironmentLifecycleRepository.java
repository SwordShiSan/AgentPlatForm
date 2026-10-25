package com.agentplatform.domain.workspace;

import com.agentplatform.domain.workspace.WorkspaceData.Actor;
import java.util.UUID;

/** Short admission transactions only; physical execution remains behind the MQ handoff. */
public interface EnvironmentLifecycleRepository {
    enum Intent { SLEEP, WAKE, DELETE }
    record Admission(UUID operationId, String intent, String status, long generation, long version) {}

    Admission admit(Actor actor, UUID workspaceId, UUID requestId, String requestHash,
                    long expectedVersion, Intent intent);
    /** Managed maintenance only; activation publishes an outbox, never executes a command. */
    boolean activatePending(UUID spaceId, UUID workspaceId);
}
