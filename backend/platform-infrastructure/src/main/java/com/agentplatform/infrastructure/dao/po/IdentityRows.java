package com.agentplatform.infrastructure.dao.po;

import java.time.Instant;
import java.util.UUID;

/** Database projections; domain objects are constructed by the repository adapter. */
public final class IdentityRows {
    private IdentityRows() {}
    public record UserRow(UUID id, String username, String passwordHash, boolean active, boolean platformAdmin) {}
    public record OperationRow(String inputHash, UUID userId, boolean bootstrap) {}
    public record SpaceRow(UUID id, String type, String role, long objectLimitBytes) {}
    public record SessionRow(String tokenHash, String csrfHash, UUID userId, Instant createdAt, Instant lastSeenAt, Instant expiresAt) {}
    public record RateRow(Instant windowStart, int attempts, Instant lockedUntil) {}
}
