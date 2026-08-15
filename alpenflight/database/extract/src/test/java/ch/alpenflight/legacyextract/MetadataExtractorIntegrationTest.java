package ch.alpenflight.legacyextract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = "extract.run-on-startup=false")
@EnabledIf(value = "dockerAvailable",
        disabledReason = "Docker unavailable — start Docker Desktop / Docker Engine to run integration tests")
class MetadataExtractorIntegrationTest {

    private static final MssqlTestContainerLifecycle MSSQL = new MssqlTestContainerLifecycle();
    private static final boolean DOCKER_AVAILABLE = tryStartContainer();

    private static boolean tryStartContainer() {
        try {
            MSSQL.start();
            return true;
        } catch (Throwable dockerUnreachable) {
            System.err.println("""
                    [alpenflight-extract] Skipping MetadataExtractorIntegrationTest — Docker unreachable.

                      Root cause: %s

                      To run the integration tests locally you need a working Docker daemon:
                        - Windows / macOS: start Docker Desktop and wait for the whale icon
                          to stop spinning. Verify with `docker info` from a fresh shell.
                        - Linux: `sudo systemctl start docker` (or `service docker start`).
                        - WSL2 on Windows: enable "Use the WSL 2 based engine" in Docker
                          Desktop settings, OR install Docker Engine inside the WSL distro.

                      The CI workflow runs Docker natively, so PRs are gated on real test runs
                      even when local dev skips.
                    """.formatted(dockerUnreachable.getMessage()));
            return false;
        }
    }

    static boolean dockerAvailable() {
        return DOCKER_AVAILABLE;
    }

    @AfterAll
    static void stopContainer() {
        MSSQL.stop();
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MSSQL::jdbcUrl);
        r.add("spring.datasource.username", MSSQL::username);
        r.add("spring.datasource.password", MSSQL::password);
        r.add("spring.datasource.driver-class-name", () -> "com.microsoft.sqlserver.jdbc.SQLServerDriver");
    }

    @Autowired DataSource dataSource;
    @Autowired MetadataExtractor extractor;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path LEGACY_FLSTEST_FIXTURE_ROOT = locateFlsTestFixture();
    private static final int MINIMUM_APPLIED_BATCHES_PROVING_THE_FIXTURE_WAS_READ = 50;
    private static final int MAX_PARENT_DIRECTORIES_WALKED_TO_REPO_ROOT = 6;

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
        LegacyExtractFixtureSeeder.SeedResult result =
                LegacyExtractFixtureSeeder.applyAll(ds, LEGACY_FLSTEST_FIXTURE_ROOT);
        assertThat(result.batchesApplied())
                .as("FLSTest fixture seed produced no successful batches")
                .isGreaterThan(MINIMUM_APPLIED_BATCHES_PROVING_THE_FIXTURE_WAS_READ);
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

        assertThat(out.resolve("row-counts.json")).doesNotExist();
        assertThat(out.resolve("storage-stats.json")).doesNotExist();
        assertThat(out.resolve("column-cardinality.json")).doesNotExist();
        assertThat(out.resolve("audit-log-sizing.json")).doesNotExist();
    }

    @Test
    void tables_json_lists_known_fls_tables(@TempDir Path out) throws IOException {
        extractor.extractTo(new ExtractConfig(false, true, out));
        JsonNode tables = JSON.readTree(out.resolve("tables.json").toFile());

        var tableNames = nodeValues(tables, "table_name");
        assertThat(tableNames).contains(
                "Flights", "Persons", "Aircrafts", "Clubs", "PersonClub",
                "FlightCrew",
                "AuditLogs", "AuditLogDetails",
                "AccountingRuleFilters",
                "Deliveries", "DeliveryItems");
    }

    @Test
    void columns_json_carries_flight_columns_with_types(@TempDir Path out) throws IOException {
        extractor.extractTo(new ExtractConfig(false, true, out));
        JsonNode columns = JSON.readTree(out.resolve("columns.json").toFile());

        long flightColumnCount = 0;
        boolean hasFlightDate = false;
        boolean hasNoClubId = true;
        for (JsonNode column : columns) {
            if ("Flights".equals(column.get("table_name").asText())) {
                flightColumnCount++;
                String name = column.get("column_name").asText();
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
        assertThat(out.resolve("audit-log-sizing.json")).exists();
        assertThat(out.resolve("cutover-window.json")).exists();
    }

    @Test
    void cutover_window_json_carries_top10_with_migrate_seconds(@TempDir Path out) throws IOException {
        extractor.extractTo(new ExtractConfig(true, true, out));
        JsonNode cutoverWindow = JSON.readTree(out.resolve("cutover-window.json").toFile());

        assertThat(cutoverWindow.get("throughput_mb_per_sec").asDouble())
                .as("default throughput constant is 30 MB/s per the refinement worked example")
                .isEqualTo(30.0);
        assertThat(cutoverWindow.get("budget_seconds").asLong())
                .as("C6 sacred-cow cutover budget is 6 hours = 21600 s")
                .isEqualTo(21_600L);

        JsonNode top = cutoverWindow.get("top_tables");
        assertThat(top.isArray()).isTrue();
        assertThat(top.size())
                .as("top_tables should be non-empty for the seeded fixture")
                .isGreaterThan(0)
                .isLessThanOrEqualTo(10);

        JsonNode first = top.get(0);
        assertThat(first.get("table").asText()).isNotBlank();
        assertThat(first.get("storage_mb").asDouble()).isNotNegative();
        assertThat(first.get("migrate_seconds").asDouble()).isNotNegative();
        assertThat(first.get("reindex_seconds").asDouble()).isNotNegative();
        assertThat(first.get("subtotal_seconds").asDouble()).isNotNegative();
        assertThat(first.get("pct_of_budget").asDouble())
                .as("at FLSTest scale, bulk-data migration is < 1% of cutover budget — surfaces the S-016 'don't over-engineer parallel load' finding")
                .isLessThan(5.0);
    }


    @Test
    void tenant_classification_json_is_emitted(@TempDir Path out) {
        extractor.extractTo(new ExtractConfig(false, true, out));
        assertThat(out.resolve("tenant-classification.json"))
                .as("S-011 catalog: tenant-classification.json is the machine-readable contract S-022/S-023/S-024/S-025 consume")
                .exists();
    }

    @Test
    void tenant_classification_covers_every_legacy_table(@TempDir Path out) throws IOException {
        extractor.extractTo(new ExtractConfig(false, true, out));

        JsonNode tables = JSON.readTree(out.resolve("tables.json").toFile());
        JsonNode classification = JSON.readTree(out.resolve("tenant-classification.json").toFile());

        var legacyTables = new java.util.HashSet<String>();
        tables.forEach(n -> legacyTables.add(n.get("table_name").asText()));

        var classified = new java.util.HashSet<String>();
        classification.get("entities").forEach(n -> classified.add(n.get("legacy_table").asText()));

        assertThat(classified)
                .as("every legacy table from tables.json must be classified — completeness verifier")
                .containsAll(legacyTables);
        assertThat(legacyTables)
                .as("no classification entry references a missing legacy table — orphan verifier")
                .containsAll(classified);
    }

    @Test
    void tenant_classification_buckets_are_from_closed_vocabulary(@TempDir Path out) throws IOException {
        extractor.extractTo(new ExtractConfig(false, true, out));
        JsonNode classification = JSON.readTree(out.resolve("tenant-classification.json").toFile());

        var allowedScopes = java.util.Set.of(
                "TENANT_SCOPED", "CROSS_TENANT", "SYSTEM_GLOBAL",
                "REFERENCE_DATA", "INDIRECT_TENANT", "PRINCIPAL_SUBJECT");

        for (JsonNode entry : classification.get("entities")) {
            String legacyScope = entry.get("legacy_scope").asText();
            String targetScope = entry.get("target_scope").asText();
            assertThat(allowedScopes)
                    .as("legacy_scope for %s must be in the closed vocabulary", entry.get("legacy_table").asText())
                    .contains(legacyScope);
            assertThat(allowedScopes)
                    .as("target_scope for %s must be in the closed vocabulary", entry.get("legacy_table").asText())
                    .contains(targetScope);
        }
    }

    @Test
    void tenant_classification_flights_is_indirect_tenant_legacy_and_tenant_scoped_target(@TempDir Path out) throws IOException {
        extractor.extractTo(new ExtractConfig(false, true, out));
        JsonNode classification = JSON.readTree(out.resolve("tenant-classification.json").toFile());

        JsonNode flights = findClassification(classification, "Flights");
        assertThat(flights.get("legacy_scope").asText()).isEqualTo("INDIRECT_TENANT");
        assertThat(flights.get("target_scope").asText()).isEqualTo("TENANT_SCOPED");
        assertThat(flights.get("target_entity").asText()).isEqualTo("Flight");
        var preconditions = new java.util.ArrayList<String>();
        flights.get("preconditions").forEach(n -> preconditions.add(n.asText()));
        assertThat(preconditions)
                .as("Flights classification must surface the S-013 denormalization precondition")
                .anySatisfy(p -> assertThat(p).containsIgnoringCase("denormalize"));
    }

    @Test
    void tenant_classification_known_gray_area_entities(@TempDir Path out) throws IOException {
        extractor.extractTo(new ExtractConfig(false, true, out));
        JsonNode classification = JSON.readTree(out.resolve("tenant-classification.json").toFile());

        assertScope(classification, "Persons", "CROSS_TENANT");
        assertScope(classification, "PersonClub", "TENANT_SCOPED");
        assertScope(classification, "Users", "PRINCIPAL_SUBJECT");
        assertScope(classification, "AuditLogs", "TENANT_SCOPED");
        assertScope(classification, "AuditLogDetails", "TENANT_SCOPED");
        assertScope(classification, "Countries", "REFERENCE_DATA");
        assertScope(classification, "LanguageTranslations", "REFERENCE_DATA");
    }

    @Test
    void tenant_classification_person_club_is_the_membership_pivot_carrying_the_club_discriminator(@TempDir Path out)
            throws IOException {
        extractor.extractTo(new ExtractConfig(false, true, out));
        JsonNode classification = JSON.readTree(out.resolve("tenant-classification.json").toFile());

        JsonNode personClub = findClassification(classification, "PersonClub");
        assertThat(personClub.get("tenant_column").asText())
                .as("PersonClub is the one row per (person, club) membership pivot — a multi-club pilot keeps one "
                        + "row per club, each discriminated by club_id, while Persons stays CROSS_TENANT")
                .isEqualTo("club_id");
        assertThat(personClub.get("target_entity").asText()).isEqualTo("PersonClub");
    }

    @Test
    void tenant_classification_pii_blob_flag_on_audit_log_details(@TempDir Path out) throws IOException {
        extractor.extractTo(new ExtractConfig(false, true, out));
        JsonNode classification = JSON.readTree(out.resolve("tenant-classification.json").toFile());

        JsonNode auditDetails = findClassification(classification, "AuditLogDetails");
        assertThat(auditDetails.get("pii_blob").asBoolean())
                .as("AuditLogDetails carries OriginalValue/NewValue blob columns — must be flagged pii_blob:true for S-027")
                .isTrue();
    }

    @Test
    void tenant_classification_curation_md_exists() {
        Path catalogMd = locateRepoFile("alpenflight/database/tenant-catalog.md");
        assertThat(catalogMd).as("S-011 deliverable: curation MD exists at alpenflight/database/tenant-catalog.md").exists();
    }

    @Test
    void tenant_classification_rules_yaml_exists() {
        Path rulesYaml = locateRepoFile("alpenflight/database/tenant-rules.yaml");
        assertThat(rulesYaml).as("S-011 deliverable: committed YAML overrides exist at alpenflight/database/tenant-rules.yaml").exists();
    }

    @Test
    void native_sql_register_exists() {
        Path register = locateRepoFile("alpenflight/database/native-sql-register.md");
        assertThat(register).as("S-011 deliverable: native-SQL escape-hatch register file exists (initially empty per security plan)").exists();
    }

    private static JsonNode findClassification(JsonNode classification, String legacyTable) {
        for (JsonNode entry : classification.get("entities")) {
            if (legacyTable.equals(entry.get("legacy_table").asText())) {
                return entry;
            }
        }
        throw new AssertionError("classification entry not found for legacy table: " + legacyTable);
    }

    private static void assertScope(JsonNode classification, String legacyTable, String expectedTargetScope) {
        for (JsonNode entry : classification.get("entities")) {
            if (legacyTable.equals(entry.get("legacy_table").asText())) {
                assertThat(entry.get("target_scope").asText())
                        .as("%s expected target_scope=%s", legacyTable, expectedTargetScope)
                        .isEqualTo(expectedTargetScope);
                return;
            }
        }
        throw new AssertionError("legacy table not classified: " + legacyTable);
    }

    private static Path locateRepoFile(String repoRelativePath) {
        Path cursor = Paths.get(".").toAbsolutePath().normalize();
        for (int i = 0; i < MAX_PARENT_DIRECTORIES_WALKED_TO_REPO_ROOT; i++) {
            Path candidate = cursor.resolve(repoRelativePath);
            if (candidate.toFile().exists()) {
                return candidate;
            }
            cursor = cursor.getParent();
            if (cursor == null) break;
        }
        return Paths.get(repoRelativePath);
    }


    private static Path locateFlsTestFixture() {
        Path cursor = Paths.get(".").toAbsolutePath().normalize();
        for (int i = 0; i < MAX_PARENT_DIRECTORIES_WALKED_TO_REPO_ROOT; i++) {
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
