package com.agentplatform.infrastructure.storage;

import com.agentplatform.domain.harness.HarnessPorts.StateStorage;
import com.agentplatform.types.harness.HarnessData.Scope;
import com.agentplatform.types.harness.HarnessData.StateSlot;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** SQL CAS for SDK-private JSON only; canonical task/operation facts are separate. */
public final class PostgresStateStorage implements StateStorage {
    private final String url;
    private final String user;
    private final String password;

    public PostgresStateStorage(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    public Connection connect() throws SQLException { return DriverManager.getConnection(url, user, password); }

    private static void identity(PreparedStatement statement, Scope scope, String key) throws SQLException {
        statement.setObject(1, UUID.fromString(scope.spaceId()));
        statement.setObject(2, UUID.fromString(scope.actorId()));
        statement.setObject(3, UUID.fromString(scope.conversationId()));
        if (key != null) statement.setString(4, key);
    }

    @Override public StateSlot load(Scope scope, String key) {
        try (var connection = connect(); var query = connection.prepareStatement(
                "SELECT version,payload_json::text,framework_version FROM pf_runtime.state_slot WHERE space_id=? AND user_id=? AND conversation_id=? AND state_key=?")) {
            identity(query, scope, key);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) return new StateSlot(0, null);
                if (!"2.0.4".equals(rows.getString(3))) throw new IllegalStateException("STATE_FRAMEWORK_INCOMPATIBLE");
                return new StateSlot(rows.getLong(1), rows.getString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (SQLException failure) { throw unavailable(failure); }
    }

    @Override public long compareAndSwap(Scope scope, String key, long expectedVersion, byte[] body) {
        if (expectedVersion < 0 || body == null || key == null || key.isBlank()) throw new IllegalArgumentException("Invalid state CAS");
        String sql = expectedVersion == 0
                ? "INSERT INTO pf_runtime.state_slot(space_id,user_id,conversation_id,state_key,version,framework_version,payload_json) VALUES(?,?,?,?,1,'2.0.4',?::jsonb) ON CONFLICT DO NOTHING RETURNING version"
                : "UPDATE pf_runtime.state_slot SET payload_json=?::jsonb,version=version+1,updated_at=now() WHERE space_id=? AND user_id=? AND conversation_id=? AND state_key=? AND version=? AND framework_version='2.0.4' RETURNING version";
        try (var connection = connect(); var query = connection.prepareStatement(sql)) {
            if (expectedVersion == 0) {
                identity(query, scope, key);
                query.setString(5, new String(body, StandardCharsets.UTF_8));
            } else {
                query.setString(1, new String(body, StandardCharsets.UTF_8));
                query.setObject(2, UUID.fromString(scope.spaceId()));
                query.setObject(3, UUID.fromString(scope.actorId()));
                query.setObject(4, UUID.fromString(scope.conversationId()));
                query.setString(5, key);
                query.setLong(6, expectedVersion);
            }
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("STATE_CAS_CONFLICT");
                return rows.getLong(1);
            }
        } catch (SQLException failure) { throw unavailable(failure); }
    }

    @Override public Set<String> keys(Scope scope) {
        try (var connection = connect(); var query = connection.prepareStatement(
                "SELECT state_key FROM pf_runtime.state_slot WHERE space_id=? AND user_id=? AND conversation_id=?")) {
            identity(query, scope, null);
            Set<String> keys = new HashSet<>();
            try (var rows = query.executeQuery()) { while (rows.next()) keys.add(rows.getString(1)); }
            return Set.copyOf(keys);
        } catch (SQLException failure) { throw unavailable(failure); }
    }

    @Override public void delete(Scope scope, String key) {
        try (var connection = connect(); var query = connection.prepareStatement(
                "DELETE FROM pf_runtime.state_slot WHERE space_id=? AND user_id=? AND conversation_id=? AND state_key=?")) {
            identity(query, scope, key);
            query.executeUpdate();
        } catch (SQLException failure) { throw unavailable(failure); }
    }

    private static IllegalStateException unavailable(SQLException cause) {
        return new IllegalStateException("STATE_STORAGE_UNAVAILABLE", cause);
    }
}
