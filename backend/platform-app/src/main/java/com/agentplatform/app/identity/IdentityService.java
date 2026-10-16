package com.agentplatform.app.identity;

import com.agentplatform.api.identity.IdentityApi;
import com.agentplatform.domain.identity.IdentityFailure;
import com.agentplatform.domain.identity.IdentityRules;
import com.agentplatform.domain.identity.IdentitySecrets;
import com.agentplatform.domain.identity.IdentityStore;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public final class IdentityService implements IdentityApi {
    private final IdentityStore store;
    private final IdentitySecrets secrets;
    private final Clock clock;
    private final Set<String> origins;
    private final String dummy;
    public IdentityService(IdentityStore store, IdentitySecrets secrets, Clock clock, Set<String> origins) {
        this.store = store; this.secrets = secrets; this.clock = clock; this.origins = Set.copyOf(origins);
        this.dummy = secrets.passwordHash(secrets.token());
    }
    private String hash(String token) { return secrets.hash(token == null ? "" : token); }
    private IdentityStore.Session session(String token) {
        var result = store.session(hash(token), clock.instant());
        if (result == null) throw new IdentityFailure(401, "AUTHENTICATION_REQUIRED");
        return result;
    }
    private IdentityStore.Session write(String token, String csrf, String origin) {
        if (origin == null || !origins.contains(origin)) throw new IdentityFailure(403, "ORIGIN_REJECTED");
        var session = session(token);
        if (!MessageDigest.isEqual(session.csrfHash().getBytes(StandardCharsets.UTF_8), hash(csrf).getBytes(StandardCharsets.UTF_8))) throw new IdentityFailure(403, "CSRF_REJECTED");
        return session;
    }
    @Override public SessionResult csrf(String token) {
        String csrf = secrets.token();
        var old = token == null ? null : store.session(hash(token), clock.instant());
        if (old != null) { store.csrf(old.tokenHash(), hash(csrf)); return new SessionResult(token, csrf); }
        String cookie = secrets.token(); Instant now = clock.instant();
        store.createSession(new IdentityStore.Session(hash(cookie), hash(csrf), null, now, now, now.plusSeconds(900)));
        return new SessionResult(cookie, csrf);
    }
    @Override public SessionResult login(String token, String csrf, String origin, String ip, LoginRequest request) {
        write(token, csrf, origin);
        Instant now = clock.instant();
        if (!store.rate("ip:" + hash(ip), 20, 60, now)) throw new IdentityFailure(429, "LOGIN_RATE_LIMITED");
        String name;
        try { name = IdentityRules.username(request.username()); }
        catch (IdentityFailure invalid) { name = "invalid:" + hash(String.valueOf(request.username())); }
        String key = "account:" + hash(name);
        boolean locked = store.accountLocked(key, now);
        var user = store.findUser(name);
        String password = request.password();
        boolean validLength = password != null && password.length() >= 12 && password.length() <= 128;
        boolean matched = secrets.matches(validLength ? password : "invalid-password", user == null ? dummy : user.passwordHash());
        if (locked || !validLength || !matched || user == null || !user.active()) {
            if (!locked) store.accountResult(key, false, now);
            throw new IdentityFailure(401, "INVALID_CREDENTIALS");
        }
        String cookie = secrets.token(); String nextCsrf = secrets.token();
        store.authenticateSession(hash(token), user.passwordHash(), new IdentityStore.Session(hash(cookie), hash(nextCsrf), user.id(), now, now, now.plusSeconds(604800)), now);
        store.accountResult(key, true, now);
        return new SessionResult(cookie, nextCsrf);
    }
    @Override public MeResponse me(String token, String selectedSpace) {
        var session = session(token);
        if (session.userId() == null) throw new IdentityFailure(401, "AUTHENTICATION_REQUIRED");
        var user = store.user(session.userId());
        if (user == null) throw new IdentityFailure(401, "AUTHENTICATION_REQUIRED");
        if (selectedSpace != null) {
            try { store.space(user.id(), UUID.fromString(selectedSpace)); }
            catch (IllegalArgumentException invalid) { throw new IdentityFailure(400, "INVALID_SPACE_ID"); }
        }
        return new MeResponse(user.id().toString(), user.username(), user.platformAdmin(), store.spaces(user.id()).stream()
                .map(s -> new SpaceResponse(s.id().toString(), s.type(), s.role(), s.objectLimitBytes())).toList());
    }
    @Override public void logout(String token, String csrf, String origin) { var session = write(token, csrf, origin); store.revoke(session.tokenHash(), clock.instant()); }
    @Override public void verifyWrite(String token,String csrf,String origin) { write(token,csrf,origin); }
}
