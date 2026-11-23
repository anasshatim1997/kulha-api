package ma.kulha.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class AdminSupportMigrationIntegrationTest {
    private static final String V28_SCRIPT = "V28__reservation_creation_idempotency.sql";
    private static final String[] CROSS_MODULE_CONSTRAINTS = {
            "support_cases_assigned_to_fkey",
            "support_cases_customer_user_id_fkey",
            "support_cases_merchant_id_fkey",
            "support_cases_offer_id_fkey",
            "support_case_timeline_author_user_id_fkey"
    };

    @Container
    private final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("kulha_migrations")
            .withUsername("postgres")
            .withPassword("test-password");

    @Test
    void freshDatabaseMigratesThroughV28AndRetainsOnlyAdminOwnedSupportForeignKey() {
        flyway().migrate();

        assertEquals(28, currentVersion());
        assertSupportForeignKeyBoundary();
        assertTrue(tableExists("reservation", "idempotency_keys"));
        assertTrue(markerExists());
    }

    @Test
    void upgradingV26PreservesSupportRowsAndUuidReferences() {
        flyway().target("26").load().migrate();

        UUID customerId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        UUID timelineId = UUID.randomUUID();
        execute("""
                INSERT INTO identity.users (id, email, password_hash, full_name)
                VALUES ('%s', 'migration-test@kulha.local', 'test-hash', 'Migration Test')
                """.formatted(customerId));
        execute("""
                INSERT INTO admin.support_cases
                    (id, reference, category, subject, description, priority, status, customer_user_id, assigned_to)
                VALUES ('%s', 'KLH-C-MIGRATION', 'ACCOUNT', 'Preserve me', 'Original description',
                        'LOW', 'OPEN', '%s', '%s')
                """.formatted(caseId, customerId, customerId));
        execute("""
                INSERT INTO admin.support_case_timeline
                    (id, case_id, author_type, author_user_id, author_label, visibility, message)
                VALUES ('%s', '%s', 'CUSTOMER', '%s', 'Migration Test', 'CUSTOMER', 'Original timeline')
                """.formatted(timelineId, caseId, customerId));

        flyway().migrate();

        assertEquals(1, scalarLong("SELECT count(*) FROM admin.support_cases WHERE id='%s' AND customer_user_id='%s' AND assigned_to='%s' AND description='Original description'"
                .formatted(caseId, customerId, customerId)));
        assertEquals(1, scalarLong("SELECT count(*) FROM admin.support_case_timeline WHERE id='%s' AND case_id='%s' AND author_user_id='%s' AND message='Original timeline'"
                .formatted(timelineId, caseId, customerId)));
        assertSupportForeignKeyBoundary();
        assertTrue(markerExists());
    }

    @Test
    void v28RejectsMissingPrerequisiteMarkerTable(@TempDir Path tempDir) throws IOException {
        flyway().target("26").load().migrate();

        assertThrows(FlywayException.class, () -> flywayAt(tempDir).migrate());
        assertFalse(tableExists("reservation", "idempotency_keys"));
    }

    @Test
    void v28RejectsMissingPrerequisiteMarkerRow(@TempDir Path tempDir) throws IOException {
        flyway().target("26").load().migrate();
        execute("""
                CREATE TABLE admin.migration_prerequisites (
                    migration_version INTEGER PRIMARY KEY,
                    applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);

        assertThrows(FlywayException.class, () -> flywayAt(tempDir).migrate());
        assertFalse(tableExists("reservation", "idempotency_keys"));
    }

    @Test
    void failedV27RollsBackAllConstraintDropsAndDoesNotWriteMarker() {
        flyway().target("26").load().migrate();
        execute("ALTER TABLE admin.support_case_timeline DROP CONSTRAINT support_case_timeline_author_user_id_fkey");

        assertThrows(FlywayException.class, () -> flyway().migrate());

        // PostgreSQL transactional DDL must roll back the earlier four drops and marker table.
        for (int i = 0; i < CROSS_MODULE_CONSTRAINTS.length - 1; i++) {
            assertTrue(constraintExists(CROSS_MODULE_CONSTRAINTS[i]), CROSS_MODULE_CONSTRAINTS[i]);
        }
        assertFalse(tableExists("admin", "migration_prerequisites"));
        assertFalse(tableExists("reservation", "idempotency_keys"));
    }

    @Test
    void successfulV27WritesMarkerAndAllowsV28() {
        flyway().target("26").load().migrate();

        flyway().migrate();

        assertTrue(markerExists());
        assertTrue(tableExists("reservation", "idempotency_keys"));
        assertSupportForeignKeyBoundary();
    }

    private Flyway flyway() {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load();
    }

    private Flyway flywayAt(Path location) throws IOException {
        Files.writeString(location.resolve(V28_SCRIPT), resource(V28_SCRIPT));
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("filesystem:" + location.toAbsolutePath())
                .load();
    }

    private String resource(String name) throws IOException {
        try (var input = getClass().getResourceAsStream("/db/migration/" + name)) {
            assertNotNull(input, "Missing migration resource " + name);
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private void assertSupportForeignKeyBoundary() {
        for (String name : CROSS_MODULE_CONSTRAINTS) {
            assertFalse(constraintExists(name), "Cross-module FK must be absent: " + name);
        }
        assertTrue(constraintExists("support_case_timeline_case_id_fkey"),
                "Admin-owned timeline-to-case FK must remain");
        assertTrue(scalarLong("""
                SELECT count(*) FROM pg_constraint
                 WHERE conrelid = 'admin.support_case_timeline'::regclass
                   AND conname = 'support_case_timeline_case_id_fkey'
                   AND pg_get_constraintdef(oid) LIKE '%ON DELETE CASCADE%'
                """) == 1, "Timeline FK must retain ON DELETE CASCADE");
    }

    private boolean markerExists() {
        return tableExists("admin", "migration_prerequisites")
                && scalarLong("SELECT count(*) FROM admin.migration_prerequisites WHERE migration_version=27") == 1;
    }

    private boolean constraintExists(String name) {
        return scalarLong("SELECT count(*) FROM pg_constraint WHERE conname='%s'".formatted(name)) == 1;
    }

    private boolean tableExists(String schema, String table) {
        return scalarLong("SELECT count(*) FROM information_schema.tables WHERE table_schema='%s' AND table_name='%s'"
                .formatted(schema, table)) == 1;
    }

    private int currentVersion() {
        return (int) scalarLong("SELECT max(version::integer) FROM public.flyway_schema_history WHERE success");
    }

    private long scalarLong(String sql) {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        } catch (Exception exception) {
            throw new AssertionError("SQL query failed: " + sql, exception);
        }
    }

    private void execute(String sql) {
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception exception) {
            throw new AssertionError("SQL statement failed: " + sql, exception);
        }
    }

    private Connection connection() throws java.sql.SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}
