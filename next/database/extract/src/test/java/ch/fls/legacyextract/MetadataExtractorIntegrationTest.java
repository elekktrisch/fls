package ch.fls.legacyextract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end integration test. Spins up SQL Server via Testcontainers, seeds
 * it with the actual FLSTest fixture from {@code flsserver/database/FLSTest/},
 * runs the extractor, asserts the JSON outputs contain real FLS tables.
 *
 * <p>Per the test philosophy for this stack: this is the only test class.
 * No mocking, no unit-test tier, no synthetic schema. The real legacy
 * fixture is the substrate — and incidentally the substrate the operator
 * uses when re-running the extractor locally.
 */
@SpringBootTest(properties = "extract.run-on-startup=false")
@Testcontainers
class MetadataExtractorIntegrationTest {

    @Container
    static final MSSQLServerContainer<?> MSSQL =
            new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest").acceptLicense();

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MSSQL::getJdbcUrl);
        r.add("spring.datasource.username", MSSQL::getUsername);
        r.add("spring.datasource.password", MSSQL::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "com.microsoft.sqlserver.jdbc.SQLServerDriver");
    }

    @Autowired DataSource dataSource;
    @Autowired MetadataExtractor extractor;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path FLS_TEST_ROOT = locateFlsTestFixture();

    @BeforeAll
    static void verifySqlClasspathIsSafe() {
        SqlGuard.ScanReport report = SqlGuard.scanClasspathResources();
        assertThat(report.errors())
                .as("classpath SQL contains forbidden patterns: %s", report.errors())
                .isEmpty();
        assertThat(report.scannedCount())
                .as("no SQL resources scanned — loader broken or no SQL files yet")
                .isGreaterThan(0);
    }

    @BeforeAll
    static void seedFlsTestFixture(@Autowired DataSource ds) throws IOException {
        FlsTestFixtureSeeder.SeedResult result = FlsTestFixtureSeeder.applyAll(ds, FLS_TEST_ROOT);
        // The fixture must produce *some* schema for the extraction to be
        // meaningful. The exact number of failing batches isn't a strong
        // invariant — legacy scripts contain server-state DDL that won't
        // apply against a fresh container — but a complete-failure run means
        // the fixture isn't being read at all.
        assertThat(result.batchesApplied())
                .as("FLSTest fixture seed produced no successful batches")
                .isGreaterThan(50);
    }

    @Test
    void emits_all_required_json_files(@TempDir Path out) {
        extractor.extractTo(new ExtractConfig(false, true, out));

        assertThat(out.resolve("tables.json")).exists();
        assertThat(out.resolve("columns.json")).exists();
        assertThat(out.resolve("pks.json")).exists();
        assertThat(out.resolve("fks.json")).exists();
        assertThat(out.resolve("uniques.json")).exists();
        assertThat(out.resolve("checks.json")).exists();
        assertThat(out.resolve("defaults.json")).exists();
        assertThat(out.resolve("indexes.json")).exists();
        assertThat(out.resolve("views.json")).exists();
        assertThat(out.resolve("triggers.json")).exists();
        assertThat(out.resolve("identity-columns.json")).exists();
        assertThat(out.resolve("manifest.json")).exists();

        // Aggregate-gated files MUST NOT appear when the flag is off.
        assertThat(out.resolve("row-counts.json")).doesNotExist();
        assertThat(out.resolve("storage-stats.json")).doesNotExist();
        assertThat(out.resolve("column-cardinality.json")).doesNotExist();
        assertThat(out.resolve("audit-log-sizing.json")).doesNotExist();
    }

    @Test
    void tables_json_lists_known_fls_tables(@TempDir Path out) throws IOException {
        extractor.extractTo(new ExtractConfig(false, true, out));
        JsonNode tables = JSON.readTree(out.resolve("tables.json").toFile());

        // Hand-picked tables that are load-bearing for the modernization:
        //   - Flights / Persons / Aircrafts / Clubs / PersonClubs — sacred-cow domain
        //   - AuditLogs + AuditLogDetails — largest PII container
        //   - AccountingRuleFilters — rules-engine config
        //   - Deliveries / DeliveryItems — invoice flow terminal state
        var names = nodeValues(tables, "name");
        assertThat(names).contains(
                "Flights", "Persons", "Aircrafts", "Clubs", "PersonClubs",
                "AuditLogs", "AuditLogDetails",
                "AccountingRuleFilters",
                "Deliveries", "DeliveryItems");
    }

    @Test
    void columns_json_carries_flight_columns_with_types(@TempDir Path out) throws IOException {
        extractor.extractTo(new ExtractConfig(false, true, out));
        JsonNode cols = JSON.readTree(out.resolve("columns.json").toFile());

        // Spot-check Flights table — it's parity-critical and has no ClubId,
        // which is the indirect-tenancy finding the JSON output surfaces.
        long flightColumnCount = 0;
        boolean hasFlightDate = false;
        boolean hasNoClubId = true;
        for (JsonNode c : cols) {
            if ("Flights".equals(c.get("table").asText())) {
                flightColumnCount++;
                String name = c.get("name").asText();
                if ("FlightDate".equalsIgnoreCase(name)) hasFlightDate = true;
                if ("ClubId".equalsIgnoreCase(name)) hasNoClubId = false;
            }
        }
        assertThat(flightColumnCount)
                .as("Flights should have many columns — fixture didn't seed it")
                .isGreaterThan(20L);
        assertThat(hasFlightDate)
                .as("Flights.FlightDate column expected")
                .isTrue();
        assertThat(hasNoClubId)
                .as("Flights MUST NOT have a ClubId column — tenancy is indirect via AircraftId → Aircrafts.OwnerClubId. If this fails, the fixture changed and S-013's denormalization callout needs revisiting.")
                .isTrue();
    }

    @Test
    void fks_json_captures_referenced_tables(@TempDir Path out) throws IOException {
        extractor.extractTo(new ExtractConfig(false, true, out));
        JsonNode fks = JSON.readTree(out.resolve("fks.json").toFile());
        assertThat(fks.isArray()).isTrue();
        assertThat(fks.size())
                .as("FLSTest fixture should produce many foreign keys")
                .isGreaterThan(10);
        // Every FK record carries the referenced table + the columns lists.
        JsonNode first = fks.get(0);
        assertThat(first.get("table").asText()).isNotBlank();
        assertThat(first.get("referenced_table").asText()).isNotBlank();
        assertThat(first.get("columns").isArray()).isTrue();
        assertThat(first.get("referenced_columns").isArray()).isTrue();
    }

    @Test
    void indexes_json_includes_fls_indexes(@TempDir Path out) throws IOException {
        extractor.extractTo(new ExtractConfig(false, true, out));
        JsonNode indexes = JSON.readTree(out.resolve("indexes.json").toFile());
        assertThat(indexes.size())
                .as("FLSTest fixture should produce many indexes")
                .isGreaterThan(20);
    }

    @Test
    void manifest_json_records_provenance(@TempDir Path out) throws IOException {
        ExtractResult result = extractor.extractTo(new ExtractConfig(false, true, out));
        JsonNode manifest = JSON.readTree(out.resolve("manifest.json").toFile());
        assertThat(manifest.get("source_host").asText()).isNotBlank();
        assertThat(manifest.get("snapshot_date").asText()).isNotBlank();
        assertThat(manifest.get("duration_ms").asLong()).isPositive();
        assertThat(manifest.get("allow_aggregate_counts").asBoolean()).isFalse();
        assertThat(result.duration().toMillis()).isPositive();
    }

    @Test
    void aggregate_flag_produces_scale_bearing_files(@TempDir Path out) {
        extractor.extractTo(new ExtractConfig(true, true, out));

        assertThat(out.resolve("row-counts.json")).exists();
        assertThat(out.resolve("storage-stats.json")).exists();
        assertThat(out.resolve("index-sizes.json")).exists();
        assertThat(out.resolve("index-usage.json")).exists();
        assertThat(out.resolve("column-cardinality.json")).exists();
        // audit-log-sizing.json is conditional on AuditLogs+AuditLogDetails
        // existing. FLSTest fixture has both → file should be produced.
        assertThat(out.resolve("audit-log-sizing.json")).exists();
    }

    // ---- helpers ----

    private static Path locateFlsTestFixture() {
        // The test runs from the Gradle subproject (next/database/extract).
        // The FLSTest fixture lives at flsserver/database/FLSTest from the
        // repo root. Walk up until we find it; bail loudly if missing.
        Path cursor = Paths.get(".").toAbsolutePath().normalize();
        for (int i = 0; i < 6; i++) {
            Path candidate = cursor.resolve("flsserver/database/FLSTest");
            if (candidate.toFile().isDirectory()) {
                return candidate;
            }
            cursor = cursor.getParent();
            if (cursor == null) break;
        }
        throw new IllegalStateException(
                "Could not locate flsserver/database/FLSTest from working directory "
                        + Paths.get(".").toAbsolutePath());
    }

    private static java.util.List<String> nodeValues(JsonNode array, String field) {
        var out = new java.util.ArrayList<String>();
        array.forEach(n -> {
            JsonNode v = n.get(field);
            if (v != null) out.add(v.asText());
        });
        return out;
    }
}
