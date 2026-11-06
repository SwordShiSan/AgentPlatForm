package com.agentplatform.domain.chat;

import com.agentplatform.types.TaskPermissionData.Mode;
import java.util.UUID;

/** Port for the workspace-owned override aggregate. All calls use one short transaction. */
public interface WorkspaceConfigurationRepository {
    record Actor(UUID userId, UUID spaceId) {}
    record Overrides(UUID modelId, ConfigurationResolver.CredentialSource credentialSource,
                     Boolean planMode, Mode permissionMode) {}
    record View(long version, Overrides overrides) {}
    record Patch(boolean modelPresent, UUID modelId,
                 boolean credentialPresent, ConfigurationResolver.CredentialSource credentialSource,
                 boolean planPresent, Boolean planMode,
                 boolean permissionPresent, Mode permissionMode) {
        public boolean isEmpty() { return !modelPresent && !credentialPresent && !planPresent && !permissionPresent; }
    }

    boolean authorized(Actor actor, UUID workspaceId);
    void lockConfiguration(Actor actor, UUID workspaceId);
    boolean modelEnabled(UUID modelId);
    View find(Actor actor, UUID workspaceId);
    void initialize(Actor actor, UUID workspaceId);
    boolean update(Actor actor, UUID workspaceId, long expected, Patch patch);
    void audit(Actor actor, UUID workspaceId, UUID traceId);
}
