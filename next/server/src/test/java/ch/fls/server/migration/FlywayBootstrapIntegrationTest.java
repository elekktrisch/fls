package ch.fls.server.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.fls.server.testsupport.PostgresTestContainerLifecycle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * End-to-end Flyway bootstrap. Spins a Postgres 17 container via the docker
 * CLI (Testcontainers can't negotiate API ≥1.44 in this sandbox — see
 * {@link PostgresTestContainerLifecycle} JavaDoc), boots Spring Boot against
 * it, asserts {@code V1__baseline.sql} migrated and the sentinel
 * {@code app_meta} row is present.
 *
 * <p>Adversarial cases (checksum drift, out-of-order detection, clean disabled)
 * are exercised via direct Flyway API to avoid mutating the test class's own
 * autoconfig.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIf(value = "dockerAvailable",
        disabledReason = "Docker unavailable — start Docker Desktop / Docker Engine to run integration tests")
class FlywayBootstrapIntegrationTest {

    private static final PostgresTestContainerLifecycle POSTGRES = new PostgresTestContainerLifecycle();
    private static final boolean DOCKER_AVAILABLE = tryStartContainer();

    private static boolean tryStartContainer() {
        try {
            POSTGRES.start();
            return true;
        } catch (Throwable t) {
            System.err.println("""
                    [fls-server] Skipping FlywayBootstrapIntegrationTest — Docker unreachable.
                      Root cause: %s
                      Start Docker Desktop / Docker Engine and re-run.
                    """.formatted(t.getMessage()));
            return false;
        }
    }

    static boolean dockerAvailable() {
        return DOCKER_AVAILABLE;
    }

    @AfterAll
    static void stopContainer() {
        POSTGRES.stop();
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::jdbcUrl);
        r.add("spring.datasource.username", POSTGRES::username);
        r.add("spring.datasource.password", POSTGRES::password);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        r.add("spring.flyway.url", POSTGRES::jdbcUrl);
        r.add("spring.flyway.user", POSTGRES::username);
        r.add("spring.flyway.password", POSTGRES::password);
    }

    @Autowired DataSource dataSource;
    @Autowired Flyway flyway;

    @Test
    void app_boots_against_fresh_postgres() throws Exception {
        try (var conn = dataSource.getConnection();
                var stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(
                        "SELECT count(*) FROM flyway_schema_history WHERE success = true")) {
            rs.next();
            assertThat(rs.getInt(1))
                    .as("flyway_schema_history must record at least one successful migration")
                    .isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void flyway_schema_history_metadata_is_well_formed() throws Exception {
        try (var conn = dataSource.getConnection();
                var stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(
                        "SELECT version, description, type, success, installed_by, "
                                + "       script, checksum, execution_time "
                                + "FROM flyway_schema_history "
                                + "WHERE version = '1' "
                                + "ORDER BY installed_rank DESC LIMIT 1")) {
            assertThat(rs.next())
                    .as("V1 row must be present in flyway_schema_history")
                    .isTrue();
            assertThat(rs.getString("version")).isEqualTo("1");
            assertThat(rs.getString("description")).isEqualToIgnoringCase("baseline");
            assertThat(rs.getString("type")).isEqualTo("SQL");
            assertThat(rs.getBoolean("success")).isTrue();
            assertThat(rs.getString("installed_by")).isNotBlank();
            assertThat(rs.getString("script")).isEqualTo("V1__baseline.sql");
            assertThat(rs.getObject("checksum"))
                    .as("checksum must be populated for SQL migrations")
                    .isNotNull();
            assertThat(rs.getInt("execution_time"))
                    .as("execution_time is recorded in millis; non-negative")
                    .isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void placeholder_baseline_objects_exist() throws Exception {
        try (var conn = dataSource.getConnection();
                var stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(
                        "SELECT meta_value FROM app_meta WHERE meta_key = 'schema_baseline_version'")) {
            assertThat(rs.next())
                    .as("V1 inserts the schema_baseline_version sentinel row")
                    .isTrue();
            assertThat(rs.getString("meta_value")).isEqualTo("S-009");
        }
    }

    @Test
    void validate_passes_after_migrate() {
        flyway.validate();
    }

    @Test
    void clean_is_disabled() {
        assertThatThrownBy(() -> flyway.clean())
                .isInstanceOf(org.flywaydb.core.api.FlywayException.class)
                .hasMessageContaining("clean");
    }

    @Test
    void out_of_order_disabled_blocks_late_v0(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path migrationsDir = tmp.resolve("db").resolve("migration");
        Files.createDirectories(migrationsDir);
        // Existing V1 already applied to the shared container. Drop in a V0
        // that should have come "before" V1 — out-of-order=false rejects it.
        Files.writeString(
                migrationsDir.resolve("V0_5__earlier.sql"),
                "CREATE TABLE _ooo_marker (id INT PRIMARY KEY);",
                StandardCharsets.UTF_8);

        Flyway adversarial = Flyway.configure()
                .dataSource(POSTGRES.jdbcUrl(), POSTGRES.username(), POSTGRES.password())
                .locations("filesystem:" + migrationsDir.toAbsolutePath())
                .outOfOrder(false)
                .cleanDisabled(true)
                .baselineOnMigrate(false)
                .load();

        // Validate detects the unapplied lower-version migration.
        assertThatThrownBy(adversarial::validate)
                .isInstanceOf(FlywayValidateException.class);
    }

    @Test
    void checksum_drift_fails_loudly(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path isolatedDb = tmp.resolve("isolated.sql");
        Files.writeString(
                isolatedDb,
                "CREATE TABLE drift_target (id INT PRIMARY KEY);\n",
                StandardCharsets.UTF_8);
        Path migrationsDir = tmp.resolve("db").resolve("migration");
        Files.createDirectories(migrationsDir);
        Path v1 = migrationsDir.resolve("V1__drift.sql");
        Files.writeString(v1, "CREATE TABLE drift_marker (id INT PRIMARY KEY);", StandardCharsets.UTF_8);

        // Isolate from the shared container's flyway_schema_history by using a
        // disposable schema. The container is shared across tests; a dedicated
        // schema makes the adversarial migration's history independent.
        String schema = "drift_" + System.nanoTime();
        try (var conn = DriverManager.getConnection(
                POSTGRES.jdbcUrl(), POSTGRES.username(), POSTGRES.password());
                var stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA " + schema);
        }

        Flyway firstRun = Flyway.configure()
                .dataSource(POSTGRES.jdbcUrl(), POSTGRES.username(), POSTGRES.password())
                .locations("filesystem:" + migrationsDir.toAbsolutePath())
                .schemas(schema)
                .defaultSchema(schema)
                .cleanDisabled(true)
                .baselineOnMigrate(false)
                .load();
        firstRun.migrate();

        // Mutate V1 — append a comment line. Checksum changes.
        Files.writeString(
                v1,
                Files.readString(v1, StandardCharsets.UTF_8) + "\n-- drift induced by test\n",
                StandardCharsets.UTF_8);

        Flyway secondRun = Flyway.configure()
                .dataSource(POSTGRES.jdbcUrl(), POSTGRES.username(), POSTGRES.password())
                .locations("filesystem:" + migrationsDir.toAbsolutePath())
                .schemas(schema)
                .defaultSchema(schema)
                .cleanDisabled(true)
                .baselineOnMigrate(false)
                .load();
        assertThatThrownBy(secondRun::validate)
                .isInstanceOf(FlywayValidateException.class)
                .hasMessageContainingAll("checksum");
    }

    @Test
    void v1_is_the_pending_migration_count_zero_after_boot() {
        // info.pending() returns the migrations Flyway has NOT yet applied.
        // After Spring Boot autoconfig ran migrate() at startup, this must be zero.
        assertThat(flyway.info().pending())
                .as("Spring Boot autoconfig migrated V1 at startup; no pending migrations should remain")
                .isEmpty();
        assertThat(flyway.info().current().getVersion())
                .as("current schema version must be 1 (or higher if S-012+ have shipped)")
                .isGreaterThanOrEqualTo(MigrationVersion.fromVersion("1"));
    }
}
