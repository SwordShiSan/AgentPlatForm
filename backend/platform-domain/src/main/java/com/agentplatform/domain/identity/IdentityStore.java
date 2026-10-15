package com.agentplatform.domain.identity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface IdentityStore {
    record User(UUID id, String username, String passwordHash, boolean active, boolean platformAdmin) {}
    record Space(UUID id, String type, String role, long objectLimitBytes) {}
    record Session(String tokenHash, String csrfHash, UUID userId, Instant createdAt, Instant lastSeenAt, Instant expiresAt) {}
    UUID provision(UUID requestId, String fingerprint, String username, String passwordHash, boolean bootstrap);
    User findUser(String normalizedUsername);
    User user(UUID userId);
    List<Space> spaces(UUID userId);
    Space space(UUID userId, UUID spaceId);
    Session session(String tokenHash, Instant now);
    void createSession(Session session);
    void csrf(String tokenHash, String csrfHash);
    void authenticateSession(String oldHash, String expectedPasswordHash, Session replacement, Instant now);
    void revoke(String tokenHash, Instant now);
    void changeUser(String username, String passwordHash, boolean disable, Instant now);
    boolean rate(String key, int limit, int windowSeconds, Instant now);
    boolean accountLocked(String key, Instant now);
    void accountResult(String key, boolean success, Instant now);
    boolean ready();
}
