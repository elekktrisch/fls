package ch.alpenflight.legacyextract;

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

@Component
public class MetadataExtractor {

    private static final double DEFAULT_BULK_MIGRATE_THROUGHPUT_MB_PER_SEC = 30.0;
    private static final double DEFAULT_REINDEX_THROUGHPUT_MB_PER_SEC = 50.0;
    private static final long CUTOVER_BUDGET_SECONDS = Duration.ofHours(6).toSeconds();
    private static final int CUTOVER_WINDOW_TOP_TABLES_BY_STORAGE = 10;
    private static final int COMPATIBILITY_LEVEL_INTRODUCING_APPROX_COUNT_DISTINCT = 150;
    private static final int APPROX_COUNT_DISTINCT_FALLBACK_TABLESAMPLE_PERCENT = 5;
    private static final int MAX_PARENT_DIRECTORIES_WALKED_TO_REPO_ROOT = 6;

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

        emitted.add(emitTenantClassification(config));

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


    private Path runStep(ExtractConfig config, String sqlResource, String outFile, boolean aggregate) {
        return runStep(config, sqlResource, outFile, aggregate, null);
    }

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
        return switch (groupKey) {
            case "pks", "uniques" -> groupByConstraint(rows);
            case "fks" -> groupByForeignKey(rows);
            case "indexes" -> groupByIndex(rows);
            default -> rows;
        };
    }

    private List<Map<String, Object>> groupByConstraint(List<Map<String, Object>> rows) {
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

        boolean supportsApproxCountDistinct =
                compatibilityLevel() >= COMPATIBILITY_LEVEL_INTRODUCING_APPROX_COUNT_DISTINCT;

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : indexedColumns) {
            String schema = (String) r.get("schema_name");
            String table = (String) r.get("table_name");
            String column = (String) r.get("column_name");
            String aggSql;
            String method;
            if (supportsApproxCountDistinct) {
                aggSql = String.format(
                        "SELECT APPROX_COUNT_DISTINCT([%s]) AS approx_distinct FROM [%s].[%s]",
                        column, schema, table);
                method = "APPROX_COUNT_DISTINCT";
            } else {
                aggSql = String.format(
                        "SELECT COUNT(DISTINCT [%s]) AS approx_distinct FROM [%s].[%s] TABLESAMPLE SYSTEM (%d PERCENT)",
                        column, schema, table, APPROX_COUNT_DISTINCT_FALLBACK_TABLESAMPLE_PERCENT);
                method = "COUNT_DISTINCT_TABLESAMPLE_" + APPROX_COUNT_DISTINCT_FALLBACK_TABLESAMPLE_PERCENT + "pct";
            }
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
            } catch (RuntimeException columnTypeRejectsCountDistinct) {
            }
        }
        return writeJson(config.outDir().resolve("column-cardinality.json"), out);
    }

    private Path emitCutoverWindow(ExtractConfig config) {
        String sql = readClasspath("sql/aggregate/storage-stats.sql");
        SqlGuard.assertAggregateSafe("sql/aggregate/storage-stats.sql", sql);
        List<Map<String, Object>> stats = jdbc.queryForList(sql);

        double throughput = throughputMbPerSec();
        double reindexThroughput = reindexThroughputMbPerSec();
        long budget = CUTOVER_BUDGET_SECONDS;

        List<Map<String, Object>> top = new ArrayList<>();
        int topTableCount = Math.min(stats.size(), CUTOVER_WINDOW_TOP_TABLES_BY_STORAGE);
        for (int i = 0; i < topTableCount; i++) {
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

    private Path emitTenantClassification(ExtractConfig config) {
        try {
            var tablesJson = json.readTree(config.outDir().resolve("tables.json").toFile());
            var columnsJson = json.readTree(config.outDir().resolve("columns.json").toFile());
            var fksJson = json.readTree(config.outDir().resolve("fks.json").toFile());
            var rulesYaml = ch.alpenflight.legacyextract.tenant.TenantClassifier.loadRules(locateTenantRulesYaml());

            var entries = ch.alpenflight.legacyextract.tenant.TenantClassifier.classify(
                    tablesJson, columnsJson, fksJson, rulesYaml);

            var wrapper = new LinkedHashMap<String, Object>();
            wrapper.put("version", 1);
            wrapper.put("generated_at", java.time.format.DateTimeFormatter.ISO_INSTANT.format(java.time.Instant.now()));
            wrapper.put("tenant_id_type",
                    rulesYaml != null ? rulesYaml.path("tenant_id_type").asText("Long") : "Long");
            wrapper.put("hibernate_pin",
                    rulesYaml != null ? rulesYaml.path("hibernate_pin").asText("6.x") : "6.x");
            wrapper.put("entities", entries);
            if (rulesYaml != null) {
                if (rulesYaml.has("public_flow_allowlist")) {
                    wrapper.put("public_flow_allowlist", rulesYaml.get("public_flow_allowlist"));
                }
                if (rulesYaml.has("unscoped_call_sites")) {
                    wrapper.put("unscoped_call_sites", rulesYaml.get("unscoped_call_sites"));
                }
            }
            return writeJson(config.outDir().resolve("tenant-classification.json"), wrapper);
        } catch (IOException e) {
            throw new IllegalStateException("could not emit tenant-classification.json", e);
        }
    }

    private static Path locateTenantRulesYaml() {
        Path cursor = Path.of(".").toAbsolutePath().normalize();
        for (int i = 0; i < MAX_PARENT_DIRECTORIES_WALKED_TO_REPO_ROOT; i++) {
            Path candidate = cursor.resolve("alpenflight/database/tenant-rules.yaml");
            if (Files.exists(candidate)) {
                return candidate;
            }
            cursor = cursor.getParent();
            if (cursor == null) break;
        }
        return null;
    }

    private Path emitAuditLogSizing(ExtractConfig config) {
        String sql = readClasspath("sql/aggregate/audit-log-sizing.sql");
        SqlGuard.assertAggregateSafe("sql/aggregate/audit-log-sizing.sql", sql);
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList(sql);
        } catch (RuntimeException auditLogTablesAbsentFromThisDatabase) {
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
            json.findAndRegisterModules();
            json.writeValue(file.toFile(), data);
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("could not write " + file, e);
        }
    }

    private static double throughputMbPerSec() {
        return parseDoubleProp("extract.throughput.mb-per-sec", DEFAULT_BULK_MIGRATE_THROUGHPUT_MB_PER_SEC);
    }

    private static double reindexThroughputMbPerSec() {
        return parseDoubleProp("extract.reindex-throughput.mb-per-sec", DEFAULT_REINDEX_THROUGHPUT_MB_PER_SEC);
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
