package com.agentplatform.infrastructure.storage;

import com.agentplatform.types.harness.HarnessData.Scope;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit real-environment integration selection; never counted by default host unit suite. */
class PostgresStateStorageIT {
    private PostgresStateStorage store() throws Exception {
        return new PostgresStateStorage(required("PLATFORM_PROBE_JDBC_URL"), "ap_api",
                Files.readString(Path.of(required("PLATFORM_PROBE_PASSWORD_FILE"))).strip());
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing real probe environment: " + name);
        return value;
    }
    private static Scope scope(String space, String actor, String conversation) {
        return new Scope(space, actor, conversation, UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1, false);
    }
    private static byte[] json(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    @Test void jsonScopedCasAndCrossSpaceIsolation() throws Exception {
        var storage = store();
        var a = scope(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString());
        var b = scope(UUID.randomUUID().toString(), a.actorId(), a.conversationId());
        try {
            assertEquals(0, storage.load(a, "state").version());
            assertEquals(1, storage.compareAndSwap(a, "state", 0, json("{\"content\":\"中文😀\",\"schemaVersion\":1}")));
            assertEquals(0, storage.load(b, "state").version());
            assertTrue(storage.keys(b).isEmpty());
            assertThrows(IllegalStateException.class, () -> storage.compareAndSwap(a, "state", 0, json("{}")));
            assertEquals(2, storage.compareAndSwap(a, "state", 1, json("{\"next\":true}")));
            assertTrue(new String(storage.load(a, "state").body(), StandardCharsets.UTF_8).contains("true"));
            assertThrows(IllegalStateException.class, () -> storage.compareAndSwap(a, "state", 2, json("bad-json")));
            assertEquals(2, storage.load(a, "state").version());
            try (var connection = storage.connect(); var statement = connection.prepareStatement("UPDATE pf_runtime.state_slot SET framework_version='incompatible' WHERE space_id=?")) {
                statement.setObject(1, UUID.fromString(a.spaceId()));
                statement.executeUpdate();
            }
            assertThrows(IllegalStateException.class, () -> storage.load(a, "state"));
        } finally { storage.delete(a, "state"); storage.delete(b, "state"); }
    }

    @Test void racingInitialCasHasExactlyOneWinner() throws Exception {
        var storage = store();
        var scope = scope(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString());
        var gate = new CountDownLatch(1);
        try {
            List<CompletableFuture<Boolean>> attempts = List.of(1, 2).stream().map(i -> CompletableFuture.supplyAsync(() -> {
                try { gate.await(); storage.compareAndSwap(scope, "race", 0, json("{}")); return true; }
                catch (IllegalStateException conflict) { return false; }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            })).toList();
            gate.countDown();
            assertEquals(1, attempts.stream().map(CompletableFuture::join).filter(Boolean::booleanValue).count());
            assertEquals(1, storage.load(scope, "race").version());
        } finally { storage.delete(scope, "race"); }
    }

    @Test void rollbackLeavesNoPartialFactsAndApiCannotCreateSchema() throws Exception {
        var storage = store();
        String space = UUID.randomUUID().toString();
        try (var connection = storage.connect()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO pf_runtime.state_slot(space_id,user_id,conversation_id,state_key,version,framework_version,payload_json) VALUES('" + space + "','" + space + "','" + space + "','rollback',1,'2.0.4','{}')");
                assertThrows(SQLException.class, () -> statement.executeUpdate("SELECT 1/0"));
                connection.rollback();
                try (var rows = statement.executeQuery("SELECT count(*) FROM pf_runtime.state_slot WHERE space_id='" + space + "'")) { assertTrue(rows.next()); assertEquals(0, rows.getInt(1)); }
                assertThrows(SQLException.class, () -> statement.executeUpdate("CREATE SCHEMA forbidden_api_schema"));
                connection.rollback();
            }
        }
    }
}
