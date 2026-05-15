package ch.fls.legacyextract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads FLS legacy schema metadata from SQL Server via JDBC and writes JSON
 * record arrays under {@link ExtractConfig#outDir()}. Read-only by
 * construction: only queries against {@code INFORMATION_SCHEMA.*}, {@code
 * sys.*}, and {@code sys.dm_db_*} reach the wire; application tables are
 * touched only when {@link ExtractConfig#allowAggregateCounts()} is true and
 * only via aggregate expressions (see {@link SqlGuard}).
 *
 * <p>Sacred-cow references — these are the tables S-013 / S-016 must preserve
 * shape on, and the JSON output is the contract:
 * <ul>
 *   <li><b>Flight</b> — single-entity discriminator across glider/tow/motor;
 *       has no {@code ClubId} column. Tenancy reaches Flights via
 *       {@code AircraftId → Aircrafts.OwnerClubId}; S-013 should denormalize
 *       {@code club_id} into the new {@code flight} table.</li>
 *   <li><b>FlightCrew</b> — composite UNIQUE on (Flight, Person, CrewType).</li>
 *   <li><b>AccountingRuleFilter</b> — rules-engine config, JSONB candidate.</li>
 *   <li><b>Delivery / DeliveryItem</b> — Prepared → Booked terminal flow.</li>
 *   <li><b>User / Person / PersonClub</b> — login principal vs. human vs.
 *       human-in-club; collapse breaks multi-club pilots.</li>
 *   <li><b>AuditLogs + AuditLogDetails</b> — audit fan-out; the
 *       {@code OriginalValue} / {@code NewValue} columns on
 *       {@code AuditLogDetails} are the system's largest PII container.</li>
 * </ul>
 */
@Component
public class MetadataExtractor {

    // Cutover-window math constants (AC4).
    // Conservative end-to-end MB/s for bulk migrate (bcp out + COPY in). The
    // worked example in the refinement uses 30 MB/s; S-017 rehearsal will
    // calibrate. Override via -Dextract.throughput.mb-per-sec=N.
    private static final double DEFAULT_THROUGHPUT_MB_PER_SEC = 30.0;
    // Postgres index rebuild throughput; CREATE INDEX is parallelizable +
    // generally faster than bulk insert.
    private static final double DEFAULT_REINDEX_MB_PER_SEC = 50.0;
    // C6 sacred-cow cutover budget: ≤ 6 hours = 21,600 seconds.
    private static final long CUTOVER_BUDGET_SECONDS = 21_600L;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public MetadataExtractor(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.json = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    public ExtractResult extractTo(ExtractConfig config) {
        Objects.requireNonNull(config, "config");
        Path outDir = config.outDir();
        try {
            Files.createDirectories(outDir);
        } catch (IOException e) {
            throw new IllegalStateException("could not create out-dir " + outDir, e);
        }

        Instant start = Instant.now();
        List<Path> emitted = new ArrayList<>();

        // Always-on metadata steps. Each runs the named SQL resource and
        // writes the rows as a JSON array under outDir.
        emitted.add(runStep(config, "sql/metadata/tables.sql", "tables.json", false));
        emitted.add(runStep(config, "sql/metadata/columns.sql", "columns.json", false));
        emitted.add(runStep(config, "sql/metadata/pks.sql", "pks.json", false, "pks"));
        emitted.add(runStep(config, "sql/metadata/fks.sql", "fks.json", false, "fks"));
        emitted.add(runStep(config, "sql/metadata/uniques.sql", "uniques.json", false, "uniques"));
        emitted.add(runStep(config, "sql/metadata/checks.sql", "checks.json", false));
        emitted.add(runStep(config, "sql/metadata/defaults.sql", "defaults.json", false));
        emitted.add(runStep(config, "sql/metadata/indexes.sql", "indexes.json", false, "indexes"));
        emitted.add(runStep(config, "sql/metadata/views.sql", "views.json", false));
        emitted.add(runStep(config, "sql/metadata/triggers.sql", "triggers.json", false));
        emitted.add(runStep(config, "sql/metadata/identity-columns.sql", "identity-columns.json", false));

        if (config.allowAggregateCounts()) {
            emitted.add(runStep(config, "sql/aggregate/row-counts.sql", "row-counts.json", true));
            emitted.add(runStep(config, "sql/aggregate/storage-stats.sql", "storage-stats.json", true));
            emitted.add(runStep(config, "sql/aggregate/index-sizes.sql", "index-sizes.json", true));
            emitted.add(runStep(config, "sql/aggregate/index-usage.sql", "index-usage.json", true));
            emitted.add(emitColumnCardinality(config));
            Path auditSizing = emitAuditLogSizing(config);
            if (auditSizing != null) {
                emitted.add(auditSizing);
            }
            emitted.add(emitCutoverWindow(config));
        }

        Duration duration = Duration.between(start, Instant.now());
        emitted.add(writeManifest(config, duration));

        return new ExtractResult(outDir, List.copyOf(emitted), duration);
    }

    // ---- step runners ----

    private Path runStep(ExtractConfig config, String sqlResource, String outFile, boolean aggregate) {
        return runStep(config, sqlResource, outFile, aggregate, null);
    }

    /**
     * Runs a single metadata/aggregate step. When {@code groupKey} is non-null,
     * the result-set rows are post-processed: rows with the same group key
     * are merged into one record with an array field {@code columns} (or a
     * step-specific shape). Steps that produce one-row-per-entity (no group
     * needed) pass {@code groupKey = null}.
     */
    private Path runStep(ExtractConfig config, String sqlResource, String outFile, boolean aggregate, String groupKey) {
        String sql = readClasspath(sqlResource);
        if (aggregate) {
            SqlGuard.assertAggregateSafe(sqlResource, sql);
        } else {
            SqlGuard.assertSafe(sqlResource, sql);
        }
        List<Map<String, Object>> rows = jdbc.queryForList(sql);
        List<? extends Map<String, Object>> shaped = groupKey == null ? rows : groupRows(groupKey, rows);
        return writeJson(config.outDir().resolve(outFile), shaped);
    }

    private List<Map<String, Object>> groupRows(String groupKey, List<Map<String, Object>> rows) {
        // Group rows by (schema, table, constraint_name or index_name)
        // collapsing column-rows into a single record per group.
        return switch (groupKey) {
            case "pks", "uniques" -> groupByConstraint(rows);
            case "fks" -> groupByForeignKey(rows);
            case "indexes" -> groupByIndex(rows);
            default -> rows;
        };
    }

    private List<Map<String, Object>> groupByConstraint(List<Map<String, Object>> rows) {
        // Key: (schema, table, constraint_name). Value: record with columns[].
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String key = r.get("schema_name") + "." + r.get("table_name") + "." + r.get("constraint_name");
            Map<String, Object> entry = byKey.computeIfAbsent(key, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("schema", r.get("schema_name"));
                m.put("table", r.get("table_name"));
                m.put("constraint_name", r.get("constraint_name"));
                m.put("columns", new ArrayList<String>());
                return m;
            });
            @SuppressWarnings("unchecked")
            List<String> cols = (List<String>) entry.get("columns");
            cols.add((String) r.get("column_name"));
        }
        return new ArrayList<>(byKey.values());
    }

    private List<Map<String, Object>> groupByForeignKey(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String key = r.get("schema_name") + "." + r.get("table_name") + "." + r.get("constraint_name");
            Map<String, Object> entry = byKey.computeIfAbsent(key, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("schema", r.get("schema_name"));
                m.put("table", r.get("table_name"));
                m.put("constraint_name", r.get("constraint_name"));
                m.put("columns", new ArrayList<String>());
                m.put("referenced_schema", r.get("referenced_schema"));
                m.put("referenced_table", r.get("referenced_table"));
                m.put("referenced_columns", new ArrayList<String>());
                m.put("on_delete", r.get("on_delete"));
                m.put("on_update", r.get("on_update"));
                return m;
            });
            @SuppressWarnings("unchecked")
            List<String> cols = (List<String>) entry.get("columns");
            cols.add((String) r.get("column_name"));
            @SuppressWarnings("unchecked")
            List<String> refCols = (List<String>) entry.get("referenced_columns");
            refCols.add((String) r.get("referenced_column_name"));
        }
        return new ArrayList<>(byKey.values());
    }

    private List<Map<String, Object>> groupByIndex(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String key = r.get("schema_name") + "." + r.get("table_name") + "." + r.get("index_name");
            Map<String, Object> entry = byKey.computeIfAbsent(key, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("schema", r.get("schema_name"));
                m.put("table", r.get("table_name"));
                m.put("name", r.get("index_name"));
                m.put("type", r.get("index_type"));
                m.put("is_unique", r.get("is_unique"));
                m.put("is_primary_key", r.get("is_primary_key"));
                m.put("is_unique_constraint", r.get("is_unique_constraint"));
                m.put("columns", new ArrayList<String>());
                m.put("included_columns", new ArrayList<String>());
                m.put("filter", r.get("filter_predicate"));
                return m;
            });
            String col = (String) r.get("column_name");
            Boolean included = (Boolean) r.get("is_included_column");
            String targetKey = Boolean.TRUE.equals(included) ? "included_columns" : "columns";
            @SuppressWarnings("unchecked")
            List<String> list = (List<String>) entry.get(targetKey);
            list.add(col);
        }
        return new ArrayList<>(byKey.values());
    }

    private Path emitColumnCardinality(ExtractConfig config) {
        String enumSql = readClasspath("sql/aggregate/column-cardinality.sql");
        SqlGuard.assertAggregateSafe("sql/aggregate/column-cardinality.sql", enumSql);
        List<Map<String, Object>> indexedColumns = jdbc.queryForList(enumSql);

        // SQL Server 2019+ has APPROX_COUNT_DISTINCT (requires compatibility
        // level 150). Older instances fall back to COUNT(DISTINCT) over
        // TABLESAMPLE. Both stay aggregate-only; both pass the guard.
        boolean hasApprox = compatibilityLevel() >= 150;

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : indexedColumns) {
            String schema = (String) r.get("schema_name");
            String table = (String) r.get("table_name");
            String column = (String) r.get("column_name");
            String aggSql;
            String method;
            if (hasApprox) {
                aggSql = String.format(
                        "SELECT APPROX_COUNT_DISTINCT([%s]) AS approx_distinct FROM [%s].[%s]",
                        column, schema, table);
                method = "APPROX_COUNT_DISTINCT";
            } else {
                aggSql = String.format(
                        "SELECT COUNT(DISTINCT [%s]) AS approx_distinct FROM [%s].[%s] TABLESAMPLE SYSTEM (5 PERCENT)",
                        column, schema, table);
                method = "COUNT_DISTINCT_TABLESAMPLE_5pct";
            }
            // Guard the per-column query before execution — it's
            // dynamically-built so verify the shape against the aggregate
            // ruleset (allows aggregates against app tables, rejects bare cols).
            String guardId = "dynamic/column-cardinality/" + schema + "." + table + "." + column;
            SqlGuard.assertAggregateSafe(guardId, aggSql);
            try {
                Map<String, Object> row = jdbc.queryForMap(aggSql);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("schema", schema);
                result.put("table", table);
                result.put("column", column);
                result.put("approx_distinct", row.get("approx_distinct"));
                result.put("method", method);
                out.add(result);
            } catch (RuntimeException ignored) {
                // Some indexed columns are computed/blob/etc. and don't
                // accept COUNT(DISTINCT). Skip rather than fail the run.
            }
        }
        return writeJson(config.outDir().resolve("column-cardinality.json"), out);
    }

    /**
     * Per-top-10-table cutover-window estimate. Reuses the storage-stats
     * query (already ordered by {@code total_mb} desc) and applies the
     * conservative end-to-end throughput constant (30 MB/s default; override
     * via the {@code extract.throughput.mb-per-sec} system property) plus a
     * separate reindex throughput (50 MB/s). The C6 ≤ 6h cutover budget
     * (21,600 seconds) is the denominator for the {@code pct_of_budget}
     * column.
     *
     * <p>Surfaced finding for S-016 / S-017: at production-shape scale the
     * bulk-data step is ~0.1-1% of the budget. The window is bounded by
     * verification + ANALYZE + smoke tests, not row volume. S-016 should
     * NOT over-engineer parallel-load.
     */
    private Path emitCutoverWindow(ExtractConfig config) {
        String sql = readClasspath("sql/aggregate/storage-stats.sql");
        SqlGuard.assertAggregateSafe("sql/aggregate/storage-stats.sql", sql);
        List<Map<String, Object>> stats = jdbc.queryForList(sql);

        double throughput = throughputMbPerSec();
        double reindexThroughput = reindexThroughputMbPerSec();
        long budget = CUTOVER_BUDGET_SECONDS;

        List<Map<String, Object>> top = new ArrayList<>();
        int n = Math.min(stats.size(), 10);
        for (int i = 0; i < n; i++) {
            Map<String, Object> r = stats.get(i);
            double totalMb = toDouble(r.get("total_mb"));
            double migrate = totalMb / throughput;
            double reindex = totalMb / reindexThroughput;
            double subtotal = migrate + reindex;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("schema", r.get("schema_name"));
            row.put("table", r.get("table_name"));
            row.put("storage_mb", totalMb);
            row.put("migrate_seconds", migrate);
            row.put("reindex_seconds", reindex);
            row.put("subtotal_seconds", subtotal);
            row.put("pct_of_budget", budget > 0 ? (subtotal / budget) * 100.0 : 0.0);
            top.add(row);
        }

        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("throughput_mb_per_sec", throughput);
        wrapper.put("reindex_throughput_mb_per_sec", reindexThroughput);
        wrapper.put("budget_seconds", budget);
        wrapper.put("top_tables", top);
        return writeJson(config.outDir().resolve("cutover-window.json"), wrapper);
    }

    private Path emitAuditLogSizing(ExtractConfig config) {
        String sql = readClasspath("sql/aggregate/audit-log-sizing.sql");
        SqlGuard.assertAggregateSafe("sql/aggregate/audit-log-sizing.sql", sql);
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList(sql);
        } catch (RuntimeException e) {
            return null;
        }
        if (rows.isEmpty()) {
            return null;
        }
        return writeJson(config.outDir().resolve("audit-log-sizing.json"), rows);
    }

    private int compatibilityLevel() {
        try {
            Integer level = jdbc.queryForObject(
                    "SELECT compatibility_level FROM sys.databases WHERE database_id = DB_ID()",
                    Integer.class);
            return level == null ? 0 : level;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private Path writeManifest(ExtractConfig config, Duration duration) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("source_host", queryServerName());
        manifest.put("source_version", queryServerVersion());
        manifest.put("snapshot_date", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        manifest.put("duration_ms", duration.toMillis());
        manifest.put("allow_aggregate_counts", config.allowAggregateCounts());
        manifest.put("allow_prod", config.allowProd());
        manifest.put("app_version", appVersion());
        return writeJson(config.outDir().resolve("manifest.json"), manifest);
    }

    private String queryServerName() {
        try {
            return jdbc.queryForObject("SELECT @@SERVERNAME", String.class);
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    private String queryServerVersion() {
        try {
            return jdbc.queryForObject("SELECT @@VERSION", String.class);
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    private String appVersion() {
        Package pkg = MetadataExtractor.class.getPackage();
        return pkg.getImplementationVersion() != null ? pkg.getImplementationVersion() : "dev";
    }

    private Path writeJson(Path file, Object data) {
        try {
            // Convert java.sql.Timestamp + similar JDBC types via Jackson's
            // default handling. We don't customize date format — ISO-8601 is
            // adequate for the operator runbook + downstream tooling.
            json.findAndRegisterModules();
            json.writeValue(file.toFile(), data);
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("could not write " + file, e);
        }
    }

    private static double throughputMbPerSec() {
        return parseDoubleProp("extract.throughput.mb-per-sec", DEFAULT_THROUGHPUT_MB_PER_SEC);
    }

    private static double reindexThroughputMbPerSec() {
        return parseDoubleProp("extract.reindex-throughput.mb-per-sec", DEFAULT_REINDEX_MB_PER_SEC);
    }

    private static double parseDoubleProp(String name, double fallback) {
        String v = System.getProperty(name);
        if (v == null || v.isBlank()) return fallback;
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double toDouble(Object o) {
        if (o == null) return 0.0;
        if (o instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(o.toString());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private String readClasspath(String resource) {
        try (InputStream in = MetadataExtractor.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("classpath resource not found: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + resource, e);
        }
    }
}
