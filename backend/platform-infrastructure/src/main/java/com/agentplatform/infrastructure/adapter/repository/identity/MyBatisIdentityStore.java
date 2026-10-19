package com.agentplatform.infrastructure.adapter.repository.identity;

import com.agentplatform.domain.identity.IdentityFailure;
import com.agentplatform.domain.identity.IdentityStore;
import com.agentplatform.infrastructure.dao.identity.IdentityMapper;
import com.agentplatform.infrastructure.dao.po.IdentityRows.UserRow;
import com.agentplatform.types.Uuid7;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Mapper adapter. Atomic use cases are wrapped by the application transaction boundary. */
public final class MyBatisIdentityStore implements IdentityStore {
    private final IdentityMapper mapper;
    public MyBatisIdentityStore(IdentityMapper mapper) { this.mapper = mapper; }
    private User convert(UserRow row) {
        return row == null ? null : new User(row.id(), row.username(), row.passwordHash(), row.active(), row.platformAdmin());
    }
    @Override public UUID provision(UUID request, String fingerprint, String name, String hash, boolean bootstrap) {
        mapper.lock(bootstrap ? 1190101L : request.getMostSignificantBits());
        var operation = mapper.operation(request);
        if (operation != null) {
            if (!fingerprint.equals(operation.inputHash()) || bootstrap != operation.bootstrap()) throw new IdentityFailure(409, "IDEMPOTENCY_CONFLICT");
            return operation.userId();
        }
        if (bootstrap && mapper.bootstrapConsumed()) throw new IdentityFailure(409, "BOOTSTRAP_CONSUMED");
        UUID user = Uuid7.next(), space = Uuid7.next();
        mapper.insertUser(user, name, hash, bootstrap);
        mapper.insertSpace(space, user);
        mapper.insertMembership(space, user);
        mapper.insertStorage(space);
        mapper.insertOperation(request, fingerprint, user, bootstrap);
        return user;
    }
    @Override public User findUser(String name) { return convert(mapper.findUser(name)); }
    @Override public User user(UUID id) { return convert(mapper.user(id)); }
    @Override public List<Space> spaces(UUID user) {
        return mapper.spaces(user).stream().map(row -> new Space(row.id(), row.type(), row.role(), row.objectLimitBytes())).toList();
    }
    @Override public Space space(UUID user, UUID space) {
        return spaces(user).stream().filter(item -> item.id().equals(space)).findFirst().orElseThrow(() -> new IdentityFailure(404, "RESOURCE_NOT_FOUND"));
    }
    @Override public Session session(String token, Instant now) {
        var row = mapper.session(token, now, now.minusSeconds(86400));
        if (row == null) return null;
        mapper.touchSession(token, now);
        return new Session(row.tokenHash(), row.csrfHash(), row.userId(), row.createdAt(), row.lastSeenAt(), row.expiresAt());
    }
    private void insertSession(Session s) {
        mapper.insertSession(Uuid7.next(), s.tokenHash(), s.csrfHash(), s.userId(), s.createdAt(), s.lastSeenAt(), s.expiresAt());
    }
    @Override public void createSession(Session s) {
        mapper.pruneSessions(s.createdAt(), s.createdAt().minusSeconds(86400));
        mapper.pruneRates(s.createdAt().minusSeconds(86400), s.createdAt());
        insertSession(s);
    }
    @Override public void csrf(String token, String csrf) { mapper.csrf(token, csrf); }
    @Override public void authenticateSession(String old, String expectedPasswordHash, Session s, Instant now) {
        mapper.lock(s.userId().getLeastSignificantBits());
        var user = mapper.user(s.userId());
        if (user == null || !user.active() || !expectedPasswordHash.equals(user.passwordHash())) throw new IdentityFailure(401, "INVALID_CREDENTIALS");
        if (mapper.consumeSession(old, now, now.minusSeconds(86400)) == null) throw new IdentityFailure(403, "CSRF_REJECTED");
        insertSession(s);
    }
    @Override public void revoke(String token, Instant now) { mapper.revoke(token, now); }
    @Override public void changeUser(String name, String hash, boolean disable, Instant now) {
        var user = mapper.findUser(name);
        if (user == null) throw new IdentityFailure(404, "RESOURCE_NOT_FOUND");
        mapper.lock(user.id().getLeastSignificantBits());
        if (disable) mapper.disable(user.id()); else mapper.password(user.id(), hash);
        mapper.revokeUser(user.id(), now);
    }
    @Override public boolean rate(String key, int limit, int seconds, Instant now) { return mapper.rate(key, now, now.minusSeconds(seconds)) <= limit; }
    @Override public boolean accountLocked(String key, Instant now) {
        var row = mapper.rateRow(key);
        return row != null && row.lockedUntil() != null && row.lockedUntil().isAfter(now);
    }
    @Override public void accountResult(String key, boolean success, Instant now) {
        mapper.ensureRate(key, now);
        var row = mapper.lockRate(key);
        int attempts = success ? 0 : row.windowStart().plusSeconds(900).isAfter(now) ? row.attempts() + 1 : 1;
        mapper.accountResult(key, attempts, success || attempts == 1 ? now : row.windowStart(), !success && attempts >= 5 ? now.plusSeconds(900) : null);
    }
    @Override public boolean ready() { return mapper.ready(); }
}
