package ch.alpenflight.migrations;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.SeedReferenceUuids;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Brittleness guard (J-0c T-15, MANDATORY). The migration producer
 * ({@code ManifestBuilder} + {@code BundleWriter}) resolves a legacy Club's
 * system-global FKs ({@code country_id}, {@code club_state_id}) to the new-stack
 * seed PKs by RECOMPUTING the deterministic seed UUIDs in
 * {@link SeedReferenceUuids}. That couples the producer to the Flyway seed
 * values: if {@code V2__identity_and_reference.sql}'s {@code t_country} /
 * {@code t_club_state} INSERTs are ever reordered or regenerated, the producer
 * would silently map migrated clubs to a wrong or nonexistent seed PK.
 *
 * <p>This test parses the actual V2 seed INSERTs straight off the classpath
 * (no DB boot) and asserts {@link SeedReferenceUuids} reproduces EVERY seeded
 * row — keyed by natural key (ISO2 / code) to seed UUID — and that the row
 * counts match exactly. A seed reorder/regeneration fails this test loudly so
 * the migration can never silently map to a wrong PK. If this fails, the fix is
 * to re-pin the {@code SeedReferenceUuids} arrays/derivation to the seed (never
 * to weaken the assertion).
 */
class SeedReferenceUuidsSeedParityTest {

    // ('019e2e15-2c00-73e8-8000-0000000003e8', 'AF', 'AFG', 'Afghanistan'),
    private static final Pattern COUNTRY_ROW = Pattern.compile(
            "\\('([0-9a-fA-F-]{36})',\\s*'([A-Z]{2})'");
    // ('019e2e15-2c00-7bb8-8000-000000000bb8', 'ACTIVE',    'Active'),
    private static final Pattern CLUB_STATE_ROW = Pattern.compile(
            "\\('([0-9a-fA-F-]{36})',\\s*'([A-Z_]+)'");
    // ('019e2e15-2c00-77d0-8000-0000000007d0', 'de',    'Deutsch'),
    // codes are BCP-47: lower-case base + optional region tag (e.g. de-CH).
    private static final Pattern LANGUAGE_ROW = Pattern.compile(
            "\\('([0-9a-fA-F-]{36})',\\s*'([a-zA-Z-]+)'");

    @Test
    void recomputed_country_seed_pks_equal_the_flyway_seed() throws Exception {
        Map<String, UUID> seeded = parseSeed("t_country", COUNTRY_ROW);
        Map<String, UUID> recomputed = SeedReferenceUuids.countriesByIso2();

        assertThat(seeded)
                .as("V2 t_country must seed at least the AC-required 196 ISO countries")
                .hasSizeGreaterThanOrEqualTo(196);
        assertThat(recomputed.keySet())
                .as("SeedReferenceUuids must cover EXACTLY the seeded ISO2 set "
                        + "(a seed reorder/regeneration must fail this guard, J-0c T-15)")
                .containsExactlyInAnyOrderElementsOf(seeded.keySet());
        for (Map.Entry<String, UUID> e : seeded.entrySet()) {
            assertThat(recomputed.get(e.getKey()))
                    .as("recomputed t_country.id for ISO2 %s must equal the Flyway seed PK "
                            + "(else the producer maps Club.country_id to a wrong/nonexistent PK)",
                            e.getKey())
                    .isEqualTo(e.getValue());
        }
    }

    @Test
    void recomputed_language_seed_pks_equal_the_flyway_seed() throws Exception {
        Map<String, UUID> seeded = parseSeed("t_language", LANGUAGE_ROW);
        Map<String, UUID> recomputed = SeedReferenceUuids.languagesByCode();

        assertThat(recomputed.keySet())
                .as("SeedReferenceUuids must cover EXACTLY the seeded t_language code set "
                        + "(a seed reorder/regeneration must fail this guard, J-0c T-20)")
                .containsExactlyInAnyOrderElementsOf(seeded.keySet());
        for (Map.Entry<String, UUID> e : seeded.entrySet()) {
            assertThat(recomputed.get(e.getKey()))
                    .as("recomputed t_language.id for code %s must equal the Flyway seed PK "
                            + "(else the producer maps USER.language_id to a wrong/nonexistent PK)",
                            e.getKey())
                    .isEqualTo(e.getValue());
        }
    }

    @Test
    void recomputed_club_state_seed_pks_equal_the_flyway_seed() throws Exception {
        Map<String, UUID> seeded = parseSeed("t_club_state", CLUB_STATE_ROW);
        Map<String, UUID> recomputed = SeedReferenceUuids.clubStatesByCode();

        assertThat(recomputed.keySet())
                .as("SeedReferenceUuids must cover EXACTLY the seeded club-state code set")
                .containsExactlyInAnyOrderElementsOf(seeded.keySet());
        for (Map.Entry<String, UUID> e : seeded.entrySet()) {
            assertThat(recomputed.get(e.getKey()))
                    .as("recomputed t_club_state.id for code %s must equal the Flyway seed PK",
                            e.getKey())
                    .isEqualTo(e.getValue());
        }
    }

    /**
     * Pull the {@code INSERT INTO <table> ... VALUES ( ... );} block out of the
     * V2 baseline and parse each row's (id, naturalKey). Restricting the regex
     * scan to the single INSERT block avoids matching another table's rows.
     */
    private static Map<String, UUID> parseSeed(String table, Pattern rowPattern) throws Exception {
        String sql = readIdentityBaselineSql();
        int insertAt = sql.indexOf("INSERT INTO " + table + " ");
        assertThat(insertAt)
                .as("V2 must contain an INSERT INTO %s seed block", table)
                .isGreaterThanOrEqualTo(0);
        int end = sql.indexOf(';', insertAt);
        assertThat(end).as("the %s INSERT must terminate with ';'", table).isGreaterThan(insertAt);
        String block = sql.substring(insertAt, end);

        Map<String, UUID> byKey = new LinkedHashMap<>();
        Matcher m = rowPattern.matcher(block);
        while (m.find()) {
            UUID id = UUID.fromString(m.group(1));
            String key = m.group(2);
            byKey.put(key, id);
        }
        assertThat(byKey).as("%s seed block parsed at least one row", table).isNotEmpty();
        return byKey;
    }

    private static String readIdentityBaselineSql() throws IOException, URISyntaxException {
        URL folder = SeedReferenceUuidsSeedParityTest.class.getClassLoader()
                .getResource("db/migration");
        assertThat(folder).as("db/migration must be on the test classpath").isNotNull();
        Path dir = Paths.get(folder.toURI());
        try (var walk = Files.walk(dir, 1)) {
            Path v2 = walk
                    .filter(p -> p.getFileName().toString()
                            .matches("^V\\d+__identity_and_reference\\.sql$"))
                    .filter(p -> !p.getFileName().toString().startsWith("V1__"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "V<n>__identity_and_reference.sql not found on the test classpath"));
            return Files.readString(v2, StandardCharsets.UTF_8);
        }
    }
}
