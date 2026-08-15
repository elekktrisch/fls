package ch.alpenflight.migration.bundle.parity;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.EntityPolicy;
import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.Manifest;
import ch.alpenflight.migration.bundle.Mapper;
import ch.alpenflight.migration.bundle.identity.ClubMapper;
import ch.alpenflight.migration.bundle.identity.ClubStateMapper;
import ch.alpenflight.migration.bundle.identity.CountryMapper;
import ch.alpenflight.migration.bundle.identity.LanguageMapper;
import ch.alpenflight.migration.bundle.identity.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;

@Tag("parity")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf(value = "dockerAvailable",
        disabledReason = "Docker unavailable — start Docker Desktop / Docker Engine to run integration tests")
@DisplayName("Parity oracle — Country / Language / ClubState + Club / User")
class ParityOracleHarnessTest {

    private static final @Nullable Integer FK_ORPHANS_NOT_MEASURED = null;

    private static final MssqlContainerLifecycle MSSQL = new MssqlContainerLifecycle();
    private static final PostgresContainerLifecycle POSTGRES = new PostgresContainerLifecycle();
    private static final boolean DOCKER_AVAILABLE = tryStartContainers();

    static boolean dockerAvailable() {
        return DOCKER_AVAILABLE;
    }

    private static boolean tryStartContainers() {
        try {
            MSSQL.start();
            POSTGRES.start();
            return true;
        } catch (Throwable failure) {
            System.err.println("""
                    [parity] Skipping ParityOracleHarnessTest — Docker unreachable.
                      Root cause: %s
                    """.formatted(failure.getMessage()));
            stopContainersQuietly();
            return false;
        }
    }

    private static void stopContainersQuietly() {
        try {
            POSTGRES.stop();
        } catch (Throwable ignored) {
        }
        try {
            MSSQL.stop();
        } catch (Throwable ignored) {
        }
    }

    @AfterAll
    static void stopContainers() {
        stopContainersQuietly();
    }

    private ParityRunIdentity runIdentity;
    private List<Mapper> mappers;
    private byte[] bundleTarGzBytes;
    private LegacyFixtureSeeder.SeededFixture seededFixture;

    @BeforeAll
    void seedAndRoundTrip() throws Exception {
        runIdentity = ParityRunIdentity.fromSystemProperties();
        mappers = List.of(
                new CountryMapper(),
                new LanguageMapper(),
                new ClubStateMapper(),
                new ClubMapper(),
                new UserMapper());

        try (Connection legacyConnection = openLegacyConnection()) {
            FlsTestSchemaApplier.Result schemaResult =
                    FlsTestSchemaApplier.applyAll(legacyConnection, locateFlsTestRoot());
            assertThat(schemaResult.batchesApplied())
                    .as("Canonical FLSTest schema must apply at least one batch — "
                            + "result was %s", schemaResult)
                    .isGreaterThan(0);
            LegacyFixtureSeeder seeder = new LegacyFixtureSeeder(runIdentity.seed());
            seededFixture = seeder.seedInto(legacyConnection);
        }

        PostgresSchemaApplier.apply(POSTGRES.jdbcUrl(), POSTGRES.username(), POSTGRES.password());

        byte[] manifestBytes = buildManifestBytes();
        try (Connection legacyConnection = openLegacyConnection()) {
            bundleTarGzBytes = new ProducerHarness(legacyConnection, mappers)
                    .produceTarGz(manifestBytes);
        }
    }

    @Test
    @DisplayName("Round-trip produces zero row-count deltas and a parseable summary.json")
    void roundTripIsByteIdentical() throws Exception {
        BundleStream.Parsed parsed = BundleStream.readTarGz(
                bundleTarGzBytes, ProducerHarness.sharedJson());
        Map<EntityType, Integer> producerCounts = countProducerRows(parsed);

        ConsumerHarness.IngestOutcome ingestOutcome;
        ParityDiffEngine.DiffOutcome diffOutcome;
        try (Connection postgresConnection = openPostgresConnection();
                Connection legacyConnection = openLegacyConnection()) {
            ingestOutcome = new ConsumerHarness(postgresConnection, mappers).ingest(parsed);
            diffOutcome = ParityDiffEngine.run(legacyConnection, postgresConnection, mappers);
        }

        Path reportsDirectory = runIdentity.reportsDirectory(
                ParityRunIdentity.defaultProjectBuildDirectory());
        Path summary = ParityReports.write(reportsDirectory, runIdentity, producerCounts,
                ingestOutcome.rowCountByEntity(), diffOutcome, FK_ORPHANS_NOT_MEASURED);

        assertThat(summary).exists();
        String summaryContent = Files.readString(summary);
        assertThat(summaryContent)
                .as("summary.json must record runId, outcome flags, and explicit null fkOrphans")
                .contains("\"runId\"")
                .contains("\"passed\"")
                .contains("\"totalDeltas\"")
                .contains("\"fkOrphans\" : null");
        assertThat(diffOutcome.passed())
                .as("Row-count deltas (legacy vs new, per Club): %s",
                        diffOutcome.rowCountDeltas())
                .isTrue();
        assertThat(ingestOutcome.rowCountByEntity().get(EntityType.CLUB))
                .as("Both seeded Clubs must round-trip into t_club")
                .isEqualTo(LegacyFixtureSeeder.CLUB_COUNT);
        assertThat(ingestOutcome.rowCountByEntity().get(EntityType.USER))
                .as("Every seeded User must round-trip into t_user")
                .isEqualTo(LegacyFixtureSeeder.CLUB_COUNT * LegacyFixtureSeeder.USERS_PER_CLUB);
        assertThat(ingestOutcome.legacyIdMaps().newIdByLegacyGuidByEntity().keySet())
                .as("SYSTEM_GLOBAL FK resolution must populate legacy_id_map for "
                        + "every SYSTEM_GLOBAL mapper in the bundle")
                .containsExactlyInAnyOrder(
                        EntityType.COUNTRY, EntityType.LANGUAGE, EntityType.CLUB_STATE);
    }

    @Test
    @DisplayName("Reports directory contains no Faker PII column values")
    void reportsDoNotLeakSeededPii() throws Exception {
        Path reportsDirectory = runIdentity.reportsDirectory(
                ParityRunIdentity.defaultProjectBuildDirectory());
        assertThat(reportsDirectory)
                .as("PII smoke must run against a populated report directory — "
                        + "@BeforeAll round-trip should have written summary.json first")
                .isDirectory();
        List<String> forbiddenSubstrings = collectSeededPii();
        assertThat(forbiddenSubstrings)
                .as("Seeded fixture must contribute at least one PII substring to make "
                        + "this smoke a meaningful check")
                .isNotEmpty();
        try (var paths = Files.walk(reportsDirectory)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                String content = Files.readString(file);
                for (String forbidden : forbiddenSubstrings) {
                    assertThat(content)
                            .as("Report file %s must not echo PII column value", file)
                            .doesNotContain(forbidden);
                }
            }
        }
    }

    private List<String> collectSeededPii() {
        List<String> substrings = new ArrayList<>();
        for (LegacyFixtureSeeder.LegacyUser user : seededFixture.users()) {
            substrings.add(user.userName());
            substrings.add(user.friendlyName());
            substrings.add(user.notificationEmail());
            if (user.phoneNumber() != null) {
                substrings.add(user.phoneNumber());
            }
        }
        for (LegacyFixtureSeeder.LegacyClub club : seededFixture.clubs()) {
            substrings.add(club.clubname());
            if (club.email() != null) {
                substrings.add(club.email());
            }
            if (club.phone() != null) {
                substrings.add(club.phone());
            }
        }
        substrings.removeIf(value -> value == null || value.isBlank());
        return substrings;
    }

    private static Connection openLegacyConnection() throws Exception {
        return DriverManager.getConnection(
                MSSQL.jdbcUrl(), MSSQL.username(), MSSQL.password());
    }

    private static Connection openPostgresConnection() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.jdbcUrl(), POSTGRES.username(), POSTGRES.password());
    }

    private static Path locateFlsTestRoot() {
        Path[] candidates = new Path[] {
                Path.of("flsserver/database/FLSTest"),
                Path.of("../../flsserver/database/FLSTest"),
                Path.of("../flsserver/database/FLSTest"),
        };
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "Cannot locate flsserver/database/FLSTest from cwd "
                        + Path.of("").toAbsolutePath());
    }

    private byte[] buildManifestBytes() throws Exception {
        ObjectMapper json = ProducerHarness.sharedJson();
        Map<EntityType, EntityPolicy> entityPolicies = new EnumMap<>(EntityType.class);
        Map<EntityType, String> unmappedReason = new EnumMap<>(EntityType.class);
        for (EntityType entity : EntityType.values()) {
            switch (entity) {
                case COUNTRY, LANGUAGE, CLUB_STATE -> entityPolicies.put(entity,
                        new EntityPolicy(
                                EntityPolicy.PortPolicy.SYSTEM_GLOBAL_RESOLVE,
                                EntityPolicy.TombstonePolicy.SKIP_DELETED,
                                java.util.Set.of(),
                                java.util.List.of()));
                case CLUB, USER -> entityPolicies.put(entity,
                        new EntityPolicy(
                                EntityPolicy.PortPolicy.FULL_PORT,
                                EntityPolicy.TombstonePolicy.PORT_ALL,
                                java.util.Set.of(),
                                java.util.List.of()));
                default -> unmappedReason.put(entity, "NOT_YET_MAPPED");
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
