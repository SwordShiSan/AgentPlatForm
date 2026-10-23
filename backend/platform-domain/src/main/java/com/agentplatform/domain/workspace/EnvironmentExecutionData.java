package com.agentplatform.domain.workspace;

import java.time.Instant;
import java.util.UUID;

public final class EnvironmentExecutionData {
    private EnvironmentExecutionData() {}
    public record Publication(UUID id, UUID operationId, UUID spaceId, UUID workspaceId, UUID environmentId,
                              long generation, long fence, UUID relayOwner) {}
    public record Command(String protocol, String kind, String initiator, UUID commandId, UUID operationId,
            UUID spaceId, UUID workspaceId, UUID environmentId, UUID actorId, UUID ownerId,
            long generation, long fence, UUID creationCommandId, long creationFence,
            String imageId, String policyHash, int projectId, String containerId, String containerStartedAt, Instant deadline) {
        public SandboxRuntime.Identity physicalIdentity() {
            return new SandboxRuntime.Identity(environmentId,spaceId,workspaceId,creationCommandId,generation,creationFence,projectId);
        }
    }
    public record Receipt(UUID commandId, UUID operationId, UUID environmentId, UUID workspaceId,
            long generation, long fence, String state, String effectState, String errorCode,
            SandboxRuntime.Container container, SandboxRuntime.Stop stop, long revision) {}
}
