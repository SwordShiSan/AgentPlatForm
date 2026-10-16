package com.agentplatform.infrastructure.identity;

import com.agentplatform.domain.identity.IdentityFailure;
import com.agentplatform.domain.identity.IdentityStore;
import com.agentplatform.types.Uuid7;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class JdbcIdentityStore implements IdentityStore {
    private final String url;
    private final String username;
    private final String password;
    private final javax.sql.DataSource dataSource;
    public JdbcIdentityStore(String url, String username, String password) { this.url = url; this.username = username; this.password = password; this.dataSource = null; }
    public JdbcIdentityStore(javax.sql.DataSource dataSource) { this.url = null; this.username = null; this.password = null; this.dataSource = java.util.Objects.requireNonNull(dataSource); }
    private interface Work<T> { T run(Connection connection) throws SQLException; }
    private <T> T transaction(Work<T> work) {
        try (var c = dataSource == null ? DriverManager.getConnection(url, username, password) : dataSource.getConnection()) {
            c.setAutoCommit(false);
            try { T value = work.run(c); c.commit(); return value; }
            catch (SQLException | RuntimeException failure) { c.rollback(); throw failure; }
        } catch (SQLException failure) {
            if ("23505".equals(failure.getSQLState())) throw new IdentityFailure(409, "IDENTITY_CONFLICT");
            throw new IdentityFailure(503, "IDENTITY_UNAVAILABLE");
        }
    }
    private static PreparedStatement query(Connection c, String sql, Object... values) throws SQLException {
        var q = c.prepareStatement(sql);
        q.setQueryTimeout(5);
        for (int i = 0; i < values.length; i++) q.setObject(i + 1, values[i] instanceof Instant time ? Timestamp.from(time) : values[i]);
        return q;
    }
    private static void execute(Connection c, String sql, Object... values) throws SQLException {
        try (var q = query(c, sql, values)) { q.executeUpdate(); }
    }
    @Override public UUID provision(UUID request, String fingerprint, String name, String hash, boolean bootstrap) {
        return transaction(c -> {
            // Serialize bootstrap and request retries before reading existing operation rows.
            try (var q = query(c, "SELECT pg_advisory_xact_lock(?)", bootstrap ? 1190101L : request.getMostSignificantBits())) { q.execute(); }
            try (var q = query(c, "SELECT input_hash,user_id,bootstrap FROM pf.identity_operation WHERE request_id=?", request); var rows = q.executeQuery()) {
                if (rows.next()) {
                    if (!fingerprint.equals(rows.getString(1)) || bootstrap != rows.getBoolean(3)) throw new IdentityFailure(409, "IDEMPOTENCY_CONFLICT");
                    return rows.getObject(2, UUID.class);
                }
            }
            if (bootstrap) {
                try (var q = query(c, "SELECT 1 FROM pf.identity_operation WHERE bootstrap"); var rows = q.executeQuery()) {
                    if (rows.next()) throw new IdentityFailure(409, "BOOTSTRAP_CONSUMED");
                }
            }
            UUID user = Uuid7.next(); UUID space = Uuid7.next();
            execute(c, "INSERT INTO pf.app_user(id,username_norm,password_hash,status,platform_admin) VALUES(?,?,?,'ACTIVE',?)", user, name, hash, bootstrap);
            execute(c, "INSERT INTO pf.space(id,type,owner_user_id) VALUES(?,'PERSONAL',?)", space, user);
            execute(c, "INSERT INTO pf.membership(space_id,user_id,role,status) VALUES(?,?,'OWNER','ACTIVE')", space, user);
            execute(c, "INSERT INTO pf.space_storage(space_id) VALUES(?)", space);
            execute(c, "INSERT INTO pf.identity_operation(request_id,input_hash,user_id,bootstrap) VALUES(?,?,?,?)", request, fingerprint, user, bootstrap);
            return user;
        });
    }
    private static User readUser(ResultSet row) throws SQLException {
        return new User(row.getObject("id", UUID.class), row.getString("username_norm"), row.getString("password_hash"), "ACTIVE".equals(row.getString("status")), row.getBoolean("platform_admin"));
    }
    @Override public User findUser(String name) {
        return transaction(c -> { try (var q = query(c, "SELECT * FROM pf.app_user WHERE username_norm=?", name); var rows = q.executeQuery()) { return rows.next() ? readUser(rows) : null; } });
    }
    @Override public User user(UUID id) {
        return transaction(c -> { try (var q = query(c, "SELECT * FROM pf.app_user WHERE id=? AND status='ACTIVE'", id); var rows = q.executeQuery()) { return rows.next() ? readUser(rows) : null; } });
    }
    @Override public List<Space> spaces(UUID user) {
        return transaction(c -> {
            List<Space> result = new ArrayList<>();
            try (var q = query(c, "SELECT s.id,s.type,m.role,st.object_limit_bytes FROM pf.space s JOIN pf.membership m ON m.space_id=s.id JOIN pf.space_storage st ON st.space_id=s.id JOIN pf.app_user u ON u.id=m.user_id WHERE u.id=? AND u.status='ACTIVE' AND m.status='ACTIVE' AND (s.type<>'PERSONAL' OR s.owner_user_id=u.id) ORDER BY s.id", user); var rows = q.executeQuery()) {
                while (rows.next()) result.add(new Space(rows.getObject(1, UUID.class), rows.getString(2), rows.getString(3), rows.getLong(4)));
            }
            return List.copyOf(result);
        });
    }
    @Override public Space space(UUID user, UUID space) { return spaces(user).stream().filter(s -> s.id().equals(space)).findFirst().orElseThrow(() -> new IdentityFailure(404, "RESOURCE_NOT_FOUND")); }
    @Override public Session session(String token, Instant now) {
        return transaction(c -> {
            try (var q = query(c, "SELECT a.* FROM pf.auth_session a LEFT JOIN pf.app_user u ON u.id=a.user_id WHERE token_hash=? AND revoked_at IS NULL AND expires_at>? AND last_seen_at>? AND (a.user_id IS NULL OR u.status='ACTIVE') FOR UPDATE OF a", token, now, now.minusSeconds(86400)); var rows = q.executeQuery()) {
                if (!rows.next()) return null;
                Session result = new Session(token, rows.getString("csrf_hash"), rows.getObject("user_id", UUID.class), rows.getTimestamp("created_at").toInstant(), rows.getTimestamp("last_seen_at").toInstant(), rows.getTimestamp("expires_at").toInstant());
                execute(c, "UPDATE pf.auth_session SET last_seen_at=? WHERE token_hash=?", now, token);
                return result;
            }
        });
    }
    private static void insertSession(Connection c, Session s) throws SQLException {
        execute(c, "INSERT INTO pf.auth_session(id,token_hash,csrf_hash,user_id,created_at,last_seen_at,expires_at) VALUES(?,?,?,?,?,?,?)", Uuid7.next(), s.tokenHash(), s.csrfHash(), s.userId(), s.createdAt(), s.lastSeenAt(), s.expiresAt());
    }
    @Override public void createSession(Session s) {
        transaction(c -> {
            execute(c, "DELETE FROM pf.auth_session WHERE id IN (SELECT id FROM pf.auth_session WHERE expires_at<=? OR revoked_at<=? LIMIT 100)", s.createdAt(), s.createdAt().minusSeconds(86400));
            execute(c, "DELETE FROM pf.auth_rate WHERE bucket IN (SELECT bucket FROM pf.auth_rate WHERE window_start<? AND (locked_until IS NULL OR locked_until<?) LIMIT 100)", s.createdAt().minusSeconds(86400), s.createdAt());
            insertSession(c, s); return null;
        });
    }
    @Override public void csrf(String token, String csrf) { transaction(c -> { execute(c, "UPDATE pf.auth_session SET csrf_hash=? WHERE token_hash=? AND revoked_at IS NULL", csrf, token); return null; }); }
    @Override public void authenticateSession(String old, String expectedPasswordHash, Session s, Instant now) {
        transaction(c -> {
            accountLock(c, s.userId());
            try (var q = query(c, "SELECT status,password_hash FROM pf.app_user WHERE id=?", s.userId()); var rows = q.executeQuery()) {
                if (!rows.next() || !"ACTIVE".equals(rows.getString(1)) || !expectedPasswordHash.equals(rows.getString(2))) throw new IdentityFailure(401, "INVALID_CREDENTIALS");
            }
            try (var q = query(c, "UPDATE pf.auth_session SET revoked_at=? WHERE token_hash=? AND revoked_at IS NULL AND expires_at>? AND last_seen_at>? RETURNING id", now, old, now, now.minusSeconds(86400)); var rows = q.executeQuery()) {
                if (!rows.next()) throw new IdentityFailure(403, "CSRF_REJECTED");
            }
            insertSession(c, s); return null;
        });
    }
    @Override public void revoke(String token, Instant now) { transaction(c -> { execute(c, "UPDATE pf.auth_session SET revoked_at=? WHERE token_hash=?", now, token); return null; }); }
    @Override public void changeUser(String name, String hash, boolean disable, Instant now) {
        transaction(c -> {
            UUID id;
            try (var q = query(c, "SELECT id FROM pf.app_user WHERE username_norm=?", name); var rows = q.executeQuery()) {
                if (!rows.next()) throw new IdentityFailure(404, "RESOURCE_NOT_FOUND"); id = rows.getObject(1, UUID.class);
            }
            accountLock(c, id);
            if (disable) execute(c, "UPDATE pf.app_user SET status='DISABLED',authz_revision=authz_revision+1 WHERE id=?", id);
            else execute(c, "UPDATE pf.app_user SET password_hash=?,authz_revision=authz_revision+1 WHERE id=?", hash, id);
            execute(c, "UPDATE pf.auth_session SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL", now, id);
            return null;
        });
    }
    private static void accountLock(Connection c, UUID id) throws SQLException {
        try (var q = query(c, "SELECT pg_advisory_xact_lock(?)", id.getLeastSignificantBits())) { q.execute(); }
    }
    @Override public boolean rate(String key, int limit, int seconds, Instant now) {
        return transaction(c -> {
            try (var q = query(c, "INSERT INTO pf.auth_rate(bucket,window_start,attempts) VALUES(?,?,1) ON CONFLICT(bucket) DO UPDATE SET attempts=CASE WHEN pf.auth_rate.window_start<=? THEN 1 ELSE pf.auth_rate.attempts+1 END,window_start=CASE WHEN pf.auth_rate.window_start<=? THEN EXCLUDED.window_start ELSE pf.auth_rate.window_start END RETURNING attempts", key, now, now.minusSeconds(seconds), now.minusSeconds(seconds)); var rows = q.executeQuery()) { rows.next(); return rows.getInt(1) <= limit; }
        });
    }
    @Override public boolean accountLocked(String key, Instant now) {
        return transaction(c -> { try (var q = query(c, "SELECT locked_until FROM pf.auth_rate WHERE bucket=?", key); var rows = q.executeQuery()) { return rows.next() && rows.getTimestamp(1) != null && rows.getTimestamp(1).toInstant().isAfter(now); } });
    }
    @Override public void accountResult(String key, boolean success, Instant now) {
        transaction(c -> {
            execute(c, "INSERT INTO pf.auth_rate(bucket,window_start) VALUES(?,?) ON CONFLICT DO NOTHING", key, now);
            try (var q = query(c, "SELECT window_start,attempts,locked_until FROM pf.auth_rate WHERE bucket=? FOR UPDATE", key); var rows = q.executeQuery()) {
                rows.next();
                if (success) execute(c, "UPDATE pf.auth_rate SET attempts=0,window_start=?,locked_until=NULL WHERE bucket=?", now, key);
                else {
                    int attempts = rows.getTimestamp(1).toInstant().plusSeconds(900).isAfter(now) ? rows.getInt(2) + 1 : 1;
                    execute(c, "UPDATE pf.auth_rate SET attempts=?,window_start=?,locked_until=? WHERE bucket=?", attempts, attempts == 1 ? now : rows.getTimestamp(1).toInstant(), attempts >= 5 ? now.plusSeconds(900) : null, key);
                }
            }
            return null;
        });
    }
    @Override public boolean ready() {
            try { return transaction(c -> { try (var q = query(c, "SELECT to_regclass('pf.app_user') IS NOT NULL AND to_regclass('pf.space') IS NOT NULL AND to_regclass('pf.membership') IS NOT NULL AND to_regclass('pf.auth_session') IS NOT NULL AND to_regclass('pf.identity_operation') IS NOT NULL AND to_regclass('pf.auth_rate') IS NOT NULL AND to_regclass('pf.space_storage') IS NOT NULL AND (SELECT schema_version=1 FROM pf.baseline_version WHERE id=1)"); var rows = q.executeQuery()) { return rows.next() && rows.getBoolean(1); } }); }
        catch (IdentityFailure unavailable) { return false; }
    }
}
