package com.agentplatform.domain.workspace;

import com.agentplatform.domain.workspace.WorkspaceData.Actor;
import java.util.UUID;

/** Admission only; no Docker/HTTP work is permitted in these short transactions. */
public interface EnvironmentPreparationRepository {
    record Snapshot(UUID environmentId, UUID workspaceId, String state, long generation, long version,
                    UUID operationId, String operationKind, String operationStatus, String errorCode,
                    boolean prepareAllowed, boolean retryAllowed) {}
    /** Original operation identity survives a lost response even if the projection advanced. */
    record Submission(UUID operationId, Snapshot environment) {}
    Snapshot get(Actor actor, UUID workspaceId);
    Submission prepare(Actor actor, UUID workspaceId, UUID requestId, String requestHash, long expectedVersion, boolean retry);
}
