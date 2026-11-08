package com.agentplatform.app.knowledge;

import static org.junit.jupiter.api.Assertions.*;

import com.agentplatform.app.identity.IdentityPersistenceFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Verifies an existing V23 database and identity rows survive the additive V24 migration. */
final class KnowledgeV23UpgradePostgresTest {
    private static String url;
    private static String password;
    private static UUID userId;
    private static UUID spaceId;
    private static String database;

    @BeforeAll
    static void migrateExistingV23Database() throws Exception {
        url = System.getenv("R63_UPGRADE_TEST_JDBC_URL");
        Assumptions.assumeTrue(url != null, "Dedicated R63 V23 upgrade database not selected; NOT_RUN");
        assertEquals("1", System.getenv("R63_UPGRADE_ACCEPTANCE"));
        database = System.getenv("R63_UPGRADE_DATABASE");
        assertTrue(database != null && database.matches("r63_upgrade_[a-z0-9_]{6,48}"),
                "A uniquely named isolated R63 upgrade database is required");
        assertTrue(url.equals("jdbc:postgresql://postgres:5432/" + database + "?sslmode=disable")
                || url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[1-9][0-9]{3,4}/" + database + "\\?sslmode=disable"),
                "Use only the isolated R63 upgrade database, locally or on its private compose network");
        password = Files.readString(Path.of(System.getenv("R63_TEST_PASSWORD_FILE"))).strip();

        try (var connection = DriverManager.getConnection(url, "postgres", password);
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT to_regclass('pf.flyway_schema_history') IS NULL")) {
            assertTrue(result.next());
            assertTrue(result.getBoolean(1), "Dedicated upgrade database must be empty before the probe");
        }

        Flyway targetV23 = flyway("23");
        targetV23.migrate();
        assertEquals("23", targetV23.info().current().getVersion().toString());

        var identity = IdentityPersistenceFixture.create(url, "postgres", password);
        userId = identity.provision(UUID.randomUUID(), "r63-v23-upgrade-fixture", "r63_v23_upgrade", "fixture-only", true);
        spaceId = identity.spaces(userId).getFirst().id();

        Flyway latest = flyway(null);
        latest.migrate();
        latest.validate();
        assertEquals("24", latest.info().current().getVersion().toString());
    }

    private static Flyway flyway(String target) {
        var configuration = Flyway.configure().dataSource(url, "postgres", password)
                .defaultSchema("pf").schemas("pf", "pf_runtime");
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    @Test
    void preservesExistingV23IdentityAndRuntimeBaseline() throws Exception {
        try (var connection = DriverManager.getConnection(url, "postgres", password);
             var statement = connection.createStatement()) {
            try (var user = statement.executeQuery("SELECT count(*) FROM pf.app_user WHERE id='" + userId + "'")) {
                assertTrue(user.next());
                assertEquals(1, user.getInt(1));
            }
            try (var membership = statement.executeQuery("SELECT count(*) FROM pf.membership WHERE space_id='" + spaceId + "' AND user_id='" + userId + "'")) {
                assertTrue(membership.next());
                assertEquals(1, membership.getInt(1));
            }
            try (var baseline = statement.executeQuery("SELECT schema_version FROM pf.baseline_version WHERE id=1")) {
                assertTrue(baseline.next());
                assertEquals(1, baseline.getInt(1));
            }
            try (var objects = statement.executeQuery("SELECT to_regclass('pf.knowledge_base') IS NOT NULL AND to_regclass('pf.personal_memory') IS NOT NULL")) {
                assertTrue(objects.next());
                assertTrue(objects.getBoolean(1));
            }
        }
    }
}
