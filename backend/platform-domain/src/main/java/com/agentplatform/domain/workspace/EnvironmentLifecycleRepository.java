package com.agentplatform.domain.workspace;

import com.agentplatform.domain.workspace.WorkspaceData.Actor;
import java.util.UUID;

/** Short admission transactions only; physical execution remains behind the MQ handoff. */
public interface EnvironmentLifecycleRepository extends EnvironmentPreparationRepository {
    enum Intent { SLEEP, WAKE, DELETE, STOP }
    record Admission(UUID operationId, String intent, String status, long generation, long version) {}
    record Operation(UUID operationId,UUID workspaceId,String intent,String status,long generation,long revision,String errorCode) {}

    Admission admit(Actor actor, UUID workspaceId, UUID requestId, String requestHash,
                    long expectedVersion, Intent intent);
    /** Managed maintenance only; activation publishes an outbox, never executes a command. */
    boolean activatePending(UUID spaceId, UUID workspaceId);
    boolean activateNext();
    Operation operation(Actor actor,UUID workspaceId,UUID operationId);
    Operation request(Actor actor,UUID workspaceId,UUID requestId);
}
