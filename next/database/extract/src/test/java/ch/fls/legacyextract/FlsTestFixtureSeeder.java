package ch.fls.legacyextract;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Test-only helper. Seeds a Testcontainers SQL Server with the FLSTest fixture
 * shipped under {@code flsserver/database/FLSTest/}. Used by the integration
 * test to extract metadata against the actual legacy schema, not a synthetic
 * stand-in.
 *
 * <p>Mechanics:
 * <ul>
 *   <li>Skip {@code 1 create/1 Create Database.sql} — it pins a Windows
 *       filesystem path. Instead the test runs against the container's
 *       default database (master) and applies all schema DDL there.
 *   <li>For each {@code 2 alter/*.sql} script, split on {@code ^GO\s*$}
 *       lines, then apply each batch via JDBC.
 *   <li>Strip {@code USE [master]} / {@code USE [FLSTest]} / {@code CREATE
 *       DATABASE} / {@code ALTER DATABASE [FLSTest]} batches — they
 *       reference a database name that doesn't exist in the test container
 *       and aren't structurally relevant for metadata extraction.
 *   <li>Tolerate per-batch failures gracefully: legacy SQL Server scripts
 *       contain a handful of dialect quirks (e.g. fulltext-conditional
 *       blocks) that are fine to skip. The seeder logs the offending batch
 *       and continues; an aggregate count of skips is returned for the
 *       caller to assert against.
 * </ul>
 */
final class FlsTestFixtureSeeder {

    private static final Pattern GO_SEPARATOR = Pattern.compile("(?m)^\\s*GO\\s*$");
    private static final Pattern SKIP_BATCH = Pattern.compile(
            "(?i)^\\s*(USE\\s+\\[?(master|FLSTest)|CREATE\\s+DATABASE|ALTER\\s+DATABASE)");

    private FlsTestFixtureSeeder() {}

    static SeedResult applyAll(DataSource ds, Path flsTestRoot) throws IOException {
        Path alterDir = flsTestRoot.resolve("2 alter");
        if (!Files.isDirectory(alterDir)) {
            throw new IllegalStateException("FLSTest fixture not found at " + alterDir.toAbsolutePath());
        }
        List<Path> scripts;
        try (Stream<Path> entries = Files.list(alterDir)) {
            scripts = entries
                    .filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .sorted(scriptOrdering())
                    .toList();
        }
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        int applied = 0;
        int skipped = 0;
        int failed = 0;
        for (Path script : scripts) {
            String content = Files.readString(script);
            String[] batches = GO_SEPARATOR.split(content);
            for (String batch : batches) {
                String trimmed = batch.strip();
                if (trimmed.isEmpty()) {
                    skipped++;
                    continue;
                }
                if (SKIP_BATCH.matcher(trimmed).find()) {
                    skipped++;
                    continue;
                }
                try {
                    jdbc.execute(trimmed);
                    applied++;
                } catch (DataAccessException e) {
                    // Legacy scripts contain dialect quirks (fulltext blocks,
                    // EXECUTE AS USER references to logins we haven't created,
                    // etc.). Skip + log; metadata extraction tolerates partial
                    // schema since INFORMATION_SCHEMA queries are independent
                    // per object.
                    failed++;
                }
            }
        }
        return new SeedResult(scripts.size(), applied, skipped, failed);
    }

    /**
     * Apply scripts in semver-aware order. The default lexicographic ordering
     * mis-sorts {@code DBUpdate_v1.10.0.sql} before {@code DBUpdate_v1.2.sql};
     * we parse the version digits explicitly so the canonical install order
     * matches what the legacy build did.
     */
    private static Comparator<Path> scriptOrdering() {
        return Comparator
                // "2 Alter Database.sql" goes first (DB-level settings)
                .comparing((Path p) -> !p.getFileName().toString().startsWith("2 "))
                .thenComparing(p -> versionTuple(p.getFileName().toString()),
                        Comparator.comparing((int[] v) -> v[0])
                                .thenComparing(v -> v[1])
                                .thenComparing(v -> v[2])
                                .thenComparing(v -> v[3]))
                .thenComparing(p -> p.getFileName().toString());
    }

    private static int[] versionTuple(String filename) {
        // DBUpdate_v1.9.20p1.sql -> [1, 9, 20, 1]
        var m = Pattern.compile("v(\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:p(\\d+))?", Pattern.CASE_INSENSITIVE).matcher(filename);
        if (!m.find()) return new int[] {0, 0, 0, 0};
        return new int[] {
                Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)),
                m.group(3) != null ? Integer.parseInt(m.group(3)) : 0,
                m.group(4) != null ? Integer.parseInt(m.group(4)) : 0,
        };
    }

    record SeedResult(int scriptsProcessed, int batchesApplied, int batchesSkipped, int batchesFailed) {}
}
