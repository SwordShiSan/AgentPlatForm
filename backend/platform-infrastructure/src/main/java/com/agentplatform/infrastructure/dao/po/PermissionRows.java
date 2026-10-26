package com.agentplatform.infrastructure.dao.po;

import java.time.Instant;
import java.util.UUID;

public final class PermissionRows {
    private PermissionRows() {}
    public record PermissionRow(UUID taskId, UUID spaceId, UUID actorId, UUID conversationId, UUID workspaceId,
                                UUID rootScopeId, String mode, long version, String policyVersion,
                                String status, boolean cancelRequested, boolean planMode, Instant now) {}
    public record DirectoryRow(UUID id, String path) {}
    public record GrantRow(UUID id, UUID scopeId, String path, String capabilities, Instant expiresAt,
                           Instant revokedAt, String policyVersion) {}
    public record CommandRow(String requestHash, String responseJson) {}
}
