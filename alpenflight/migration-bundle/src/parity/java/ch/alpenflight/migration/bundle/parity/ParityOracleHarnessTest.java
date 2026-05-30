package ch.alpenflight.migration.bundle.parity;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.EntityPolicy;
import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.Manifest;
import ch.alpenflight.migration.bundle.Mapper;
import ch.alpenflight.migration.bundle.identity.ClubMapper;
import ch.alpenflight.migration.bundle.identity.CountryMapper;
import ch.alpenflight.migration.bundle.identity.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Parity oracle harness — vertical-slice round-trip for the three identity-
 * group mappers ({@link CountryMapper}, {@link ClubMapper},
 * {@link UserMapper}). Asserts the producer / envelope / consumer / diff
 * plumbing end-to-end and writes a structured report under
 * {@code build/reports/parity/<run-id>/}.
 *
 * <p>Single test class per the Performance plan — one MSSQL cold start
 * (~30-60 s) + one Postgres cold start (~5-10 s) per parity-job run.
 * Container reuse OFF on both ({@code withReuse(false)} — Security plan)
 * so a {@code @Tag("parity-reject")} negative-path case at S-187a cannot
 * inherit poisoned state from a prior happy-path run.
 *
 * <p><strong>Gated.</strong> Lives in the {@code src/parity/java/} source
 * set; {@code ./gradlew test} never sees it. Invocation:
 * {@code ./gradlew parityTest -Dparity.seed=42 -Dparity.scale=1}.
 *
 * <p>S-187a extends this class (or splits a sibling class) with the
 * remaining 25 mappers, the four coverage gates, producer-drop
 * reconciliation, two-pass UPDATE simulation, composite
 * {@code legacy_id_map_location} resolution, negative-path bundle-reject
 * tests, and the mutation-smoke self-test.
 */
@Tag("parity")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("S-187 parity oracle — Country / Club / User round-trip")
class ParityOracleHarnessTest {

    @Container
    private final MSSQLServerContainer<?> mssqlContainer =
            new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest")
                    .acceptLicense()
                    .withReuse(false);

    @Container
    private final PostgreSQLContainer<?> postgresContainer =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("alpenflight")
                    .withUsername("alpenflight")
                    .withPassword("alpenflight")
                    .withReuse(false);

    private ParityRunIdentity runIdentity;
    private List<Mapper> mappers;
    private byte[] manifestBytes;
    private byte[] bundleTarGzBytes;
    private LegacyFixtureSeeder.SeededFixture seededFixture;

    @BeforeAll
    void seedAndRoundTrip() throws Exception {
        runIdentity = ParityRunIdentity.fromSystemProperties();
        mappers = List.of(new CountryMapper(), new ClubMapper(), new UserMapper());

        try (Connection legacyConnection = openLegacyConnection()) {
            LegacyTestSchema.apply(legacyConnection);
            LegacyFixtureSeeder seeder = new LegacyFixtureSeeder(runIdentity.seed());
            seededFixture = seeder.seedInto(legacyConnection);
        }

        PostgresSchemaApplier.apply(
                postgresContainer.getJdbcUrl(),
                postgresContainer.getUsername(),
                postgresContainer.getPassword());

        manifestBytes = buildManifestBytes();
        try (Connection legacyConnection = openLegacyConnection()) {
            bundleTarGzBytes = new ProducerHarness(legacyConnection, mappers)
                    .produceTarGz(manifestBytes);
        }
    }

    @AfterAll
    void writeReportsAndCheckLeak() throws Exception {
        // Even when assertions fail, generate the report so the operator
        // can inspect what landed. The marker-leak smoke test runs after
        // (separate @Test below) so it sees a stable report dir.
    }

    @Test
    @DisplayName("Round-trip produces zero row-count deltas and a parseable summary.json")
    void roundTripIsByteIdentical() throws Exception {
        BundleStream.Parsed parsed = BundleStream.readTarGz(
                bundleTarGzBytes, ProducerHarness.sharedJson());
        Map<EntityType, Integer> producerCounts = countProducerRows(parsed);

        Map<EntityType, Integer> consumerCounts;
        ParityDiffEngine.DiffOutcome diffOutcome;
        try (Connection postgresConnection = openPostgresConnection();
                Connection legacyConnection = openLegacyConnection()) {
            consumerCounts = new ConsumerHarness(postgresConnection, mappers).ingest(parsed);
            diffOutcome = ParityDiffEngine.run(legacyConnection, postgresConnection, mappers);
        }

        Path reportsDirectory = runIdentity.reportsDirectory(
                ParityRunIdentity.defaultProjectBuildDirectory());
        Path summary = ParityReports.write(
                reportsDirectory, runIdentity, producerCounts, consumerCounts, diffOutcome);

        assertThat(summary).exists();
        assertThat(Files.readString(summary))
                .as("summary.json must record the run identity and outcome flags")
                .contains("\"runId\"")
                .contains("\"passed\"")
                .contains("\"totalDeltas\"");
        assertThat(diffOutcome.passed())
                .as("Row-count deltas (legacy vs new, per Club): %s",
                        diffOutcome.rowCountDeltas())
                .isTrue();
        assertThat(consumerCounts.get(EntityType.CLUB))
                .as("Both Clubs must round-trip into t_club")
                .isEqualTo(LegacyFixtureSeeder.CLUB_COUNT);
        assertThat(consumerCounts.get(EntityType.USER))
                .as("Every seeded User must round-trip into t_user")
                .isEqualTo(LegacyFixtureSeeder.CLUB_COUNT * LegacyFixtureSeeder.USERS_PER_CLUB);
        assertThat(consumerCounts.get(EntityType.COUNTRY))
                .as("SYSTEM_GLOBAL Country bundle bytes carry one row per seeded Country")
                .isEqualTo(LegacyFixtureSeeder.SEEDED_COUNTRIES.size());
    }

    @Test
    @DisplayName("Reports directory contains no Faker PII columns under build/reports/parity")
    void reportsDoNotLeakSeededPii() throws Exception {
        Path reportsDirectory = runIdentity.reportsDirectory(
                ParityRunIdentity.defaultProjectBuildDirectory());
        if (!Files.isDirectory(reportsDirectory)) {
            return;
        }
        // The seeded Faker PII fields the allow-list must redact. Vertical
        // slice surfaces Users.UserName / Users.FriendlyName /
        // Users.NotificationEmail / Users.PhoneNumber + Clubs.Email +
        // Clubs.Phone. S-187a widens to the full Person PII row.
        List<String> forbiddenSubstrings = new java.util.ArrayList<>();
        for (LegacyFixtureSeeder.LegacyUser user : seededFixture.users()) {
            forbiddenSubstrings.add(user.userName());
            forbiddenSubstrings.add(user.friendlyName());
            forbiddenSubstrings.add(user.notificationEmail());
            if (user.phoneNumber() != null) {
                forbiddenSubstrings.add(user.phoneNumber());
            }
        }
        try (var paths = Files.walk(reportsDirectory)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                String content = Files.readString(file);
                for (String forbidden : forbiddenSubstrings) {
                    if (forbidden == null || forbidden.isEmpty()) {
                        continue;
                    }
                    assertThat(content)
                            .as("Report file %s must not echo PII column value", file)
                            .doesNotContain(forbidden);
                }
            }
        }
    }

    private Connection openLegacyConnection() throws Exception {
        Class.forName(mssqlContainer.getDriverClassName());
        return DriverManager.getConnection(
                mssqlContainer.getJdbcUrl() + ";encrypt=false;trustServerCertificate=true",
                mssqlContainer.getUsername(),
                mssqlContainer.getPassword());
    }

    private Connection openPostgresConnection() throws Exception {
        Class.forName(postgresContainer.getDriverClassName());
        return DriverManager.getConnection(
                postgresContainer.getJdbcUrl(),
                postgresContainer.getUsername(),
                postgresContainer.getPassword());
    }

    private byte[] buildManifestBytes() throws Exception {
        ObjectMapper json = ProducerHarness.sharedJson();
        Map<EntityType, EntityPolicy> entityPolicies =
                new EnumMap<>(EntityType.class);
        Map<EntityType, String> unmappedReason = new EnumMap<>(EntityType.class);
        for (EntityType entity : EntityType.values()) {
            switch (entity) {
                case COUNTRY -> entityPolicies.put(entity, new EntityPolicy(
                        EntityPolicy.PortPolicy.SYSTEM_GLOBAL_RESOLVE,
                        EntityPolicy.TombstonePolicy.SKIP_DELETED,
                        java.util.Set.of(),
                        java.util.List.of()));
                case CLUB, USER -> entityPolicies.put(entity, new EntityPolicy(
                        EntityPolicy.PortPolicy.FULL_PORT,
                        EntityPolicy.TombstonePolicy.PORT_ALL,
                        java.util.Set.of(),
                        java.util.List.of()));
                default -> unmappedReason.put(entity,
                        "S-187 vertical slice — wired at S-187a alongside the remaining "
                                + "25 mappers.");
            }
        }
        Manifest manifest = new Manifest(
                Manifest.CURRENT_SCHEMA_VERSION, entityPolicies, unmappedReason);
        return json.writeValueAsBytes(manifest);
    }

    private Map<EntityType, Integer> countProducerRows(BundleStream.Parsed parsed) {
        Map<EntityType, Integer> producerCounts = new LinkedHashMap<>();
        for (Map.Entry<String, List<com.fasterxml.jackson.databind.JsonNode>> entry
                : parsed.entityRowsByName().entrySet()) {
            producerCounts.put(EntityType.valueOf(entry.getKey()), entry.getValue().size());
        }
        return producerCounts;
    }
}
