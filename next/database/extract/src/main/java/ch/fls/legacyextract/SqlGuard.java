package ch.fls.legacyextract;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Static SQL-string guard. Every SQL the extractor sends over the wire passes
 * through {@link #assertSafe(String, String)} (metadata-category) or
 * {@link #assertAggregateSafe(String, String)} (aggregate-gated). Forbidden
 * patterns are rejected with a message naming the resource ID, so a future
 * committer can find the source without grep-spelunking.
 *
 * <p>Rationale: a runtime guard is a belt-and-braces defense around the
 * read-only DB role. Even if the role is misconfigured or the connection
 * uses a writer login by mistake, the guard makes accidental row-data reads
 * a deterministic fail rather than a silent leak.
 *
 * <p>The guard is pessimistic: false positives (an actually-safe query that
 * trips a pattern) are easier to fix than a false negative (a row-data read
 * that slips through).
 */
public final class SqlGuard {

    // The high-PII / row-data-bearing tables. Reading these unaggregated is
    // a six-figure PII spill under FADP at production scale.
    private static final List<String> APP_TABLES = List.of(
            "Persons", "PersonClub", "Users", "AuditLogs", "AuditLogDetails",
            "Flights", "FlightCrew", "Deliveries", "DeliveryItems",
            "AccountingRuleFilters", "AircraftReservations", "PlanningDays",
            "SystemLogs");

    private static final Pattern FORBIDDEN_DDL_DML = Pattern.compile(
            "(?i)\\b(INSERT|UPDATE|DELETE|MERGE|TRUNCATE|DROP|ALTER|CREATE|EXEC|EXECUTE|GRANT|REVOKE)\\b");
    private static final Pattern DETAILED_MODE = Pattern.compile("(?i)'DETAILED'");
    private static final Pattern SAMPLED_MODE = Pattern.compile("(?i)'SAMPLED'");
    private static final Pattern SELECT_STAR = Pattern.compile(
            "(?i)\\bSELECT\\s+\\*");
    private static final Pattern AGGREGATE_FUNCS = Pattern.compile(
            "(?i)\\b(COUNT|COUNT_BIG|APPROX_COUNT_DISTINCT|MAX|MIN|SUM|AVG|STDEV|STDEVP|VAR|VARP|DATALENGTH|YEAR|MONTH|DAY)\\s*\\(");

    private SqlGuard() {}

    /**
     * Assert a metadata-category SQL is safe. Allows queries against
     * {@code INFORMATION_SCHEMA.*}, {@code sys.*}, and DMVs. Rejects any
     * reference to application tables, any DDL/DML, any SELECT *.
     */
    public static void assertSafe(String resourceId, String sql) {
        String stripped = stripComments(sql);
        rejectDdlDml(resourceId, stripped);
        rejectDetailedMode(resourceId, stripped);
        rejectSampledMode(resourceId, stripped);
        rejectSelectStarOutsideSystem(resourceId, stripped);
        rejectAppTableReference(resourceId, stripped);
    }

    /**
     * Assert an aggregate-category SQL is safe. Same as {@link #assertSafe}
     * EXCEPT app-table references are allowed when the SELECT list contains
     * only aggregate expressions (and optionally GROUP BY columns).
     */
    public static void assertAggregateSafe(String resourceId, String sql) {
        String stripped = stripComments(sql);
        rejectDdlDml(resourceId, stripped);
        rejectDetailedMode(resourceId, stripped);
        rejectSampledMode(resourceId, stripped);
        rejectSelectStarOutsideSystem(resourceId, stripped);

        String appTable = findAppTableReference(stripped);
        if (appTable == null) {
            return;
        }
        // App-table FROM clause present — verify aggregates dominate the SELECT
        // list. The conservative rule: the query must contain at least one
        // aggregate function call. Bare columns that aren't GROUP BY targets
        // are rejected.
        if (!AGGREGATE_FUNCS.matcher(stripped).find()) {
            throw violation(resourceId,
                    "app-table " + appTable + " without aggregate function — would leak row data");
        }
        rejectBareColumnsWithAggregate(resourceId, stripped, appTable);
    }

    /**
     * Walk the classpath {@code sql/} tree and assert each resource is safe.
     * Returns a structured report so a test can name the offending resources.
     */
    public static ScanReport scanClasspathResources() {
        List<String> errors = new ArrayList<>();
        int scanned = 0;
        try {
            List<Path> resourceFiles = locateClasspathSqlFiles();
            for (Path file : resourceFiles) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                String resourceId = file.toString();
                boolean isAggregate = file.toString().replace('\\', '/').contains("/sql/aggregate/");
                try {
                    if (isAggregate) {
                        assertAggregateSafe(resourceId, content);
                    } else {
                        assertSafe(resourceId, content);
                    }
                    scanned++;
                } catch (IllegalStateException e) {
                    errors.add(e.getMessage());
                }
            }
        } catch (IOException e) {
            errors.add("scan-failure: " + e.getMessage());
        }
        return new ScanReport(scanned, errors);
    }

    // ---- helpers ----

    private static String stripComments(String sql) {
        // Strip -- line comments and /* ... */ block comments before pattern
        // matching so an example-in-a-comment doesn't trip the guard.
        String noLineComments = sql.replaceAll("(?m)--.*$", "");
        return noLineComments.replaceAll("(?s)/\\*.*?\\*/", "");
    }

    private static void rejectDdlDml(String resourceId, String sql) {
        var m = FORBIDDEN_DDL_DML.matcher(sql);
        if (m.find()) {
            throw violation(resourceId, "DDL/DML/EXEC found (" + m.group(1) + ") — extraction must be read-only");
        }
    }

    private static void rejectDetailedMode(String resourceId, String sql) {
        if (DETAILED_MODE.matcher(sql).find()) {
            throw violation(resourceId, "MODE='DETAILED' is forbidden — full-page scans evict the buffer pool. Use 'LIMITED'.");
        }
    }

    private static void rejectSampledMode(String resourceId, String sql) {
        if (SAMPLED_MODE.matcher(sql).find()) {
            throw violation(resourceId, "MODE='SAMPLED' is forbidden — adds no accuracy over 'LIMITED' for our use. Use 'LIMITED'.");
        }
    }

    private static void rejectSelectStarOutsideSystem(String resourceId, String sql) {
        var m = SELECT_STAR.matcher(sql);
        while (m.find()) {
            int idx = m.end();
            // Look ahead for FROM — system tables (INFORMATION_SCHEMA, sys.*,
            // DMVs) MAY use SELECT *. Application tables MUST NOT.
            String tail = sql.substring(idx, Math.min(sql.length(), idx + 600));
            String upperTail = tail.toUpperCase();
            int fromIdx = upperTail.indexOf("FROM ");
            if (fromIdx < 0) {
                throw violation(resourceId, "SELECT * with no FROM clause — name columns explicitly");
            }
            String afterFrom = tail.substring(fromIdx + 5).stripLeading();
            String firstToken = afterFrom.split("[\\s(),;]", 2)[0];
            String upperToken = firstToken.toUpperCase();
            boolean isSystem = upperToken.startsWith("INFORMATION_SCHEMA.")
                    || upperToken.startsWith("SYS.")
                    || upperToken.startsWith("[SYS]")
                    || upperToken.startsWith("[INFORMATION_SCHEMA]");
            if (!isSystem) {
                throw violation(resourceId, "SELECT * against " + firstToken + " — name columns explicitly");
            }
        }
    }

    private static void rejectAppTableReference(String resourceId, String sql) {
        String appTable = findAppTableReference(sql);
        if (appTable != null) {
            throw violation(resourceId, "app-table " + appTable + " referenced in metadata-category SQL — gate behind --allow-aggregate-counts or remove");
        }
    }

    private static String findAppTableReference(String sql) {
        for (String name : APP_TABLES) {
            Pattern p = Pattern.compile(
                    "(?i)(\\bFROM|\\bJOIN)\\s+(\\[?dbo\\]?\\.)?\\[?" + Pattern.quote(name) + "\\]?\\b");
            if (p.matcher(sql).find()) {
                return name;
            }
        }
        return null;
    }

    private static void rejectBareColumnsWithAggregate(String resourceId, String sql, String appTable) {
        // Extract the SELECT list (text between the topmost SELECT and FROM).
        // Then check each comma-separated expression: it must contain an
        // aggregate function, OR be a column name that appears in a GROUP BY
        // clause. Pessimistic: if we can't determine, reject.
        var sm = Pattern.compile("(?is)\\bSELECT\\s+(.*?)\\bFROM\\b").matcher(sql);
        if (!sm.find()) return;
        String selectList = sm.group(1);

        String groupByCols = extractGroupByColumns(sql);
        // Split on commas at the top level (no parens depth tracking — good
        // enough for our queries where aggregates are the dominant function
        // calls and we don't nest CASE inside aggregates).
        String[] exprs = splitTopLevelComma(selectList);
        for (String expr : exprs) {
            String e = expr.strip();
            if (e.isEmpty()) continue;
            if (AGGREGATE_FUNCS.matcher(e).find()) continue;
            // Check if it's a GROUP BY column. Normalize "Schema.Column" or
            // "Alias.Column" to the rightmost identifier.
            String last = e.split("\\s+AS\\s+", 2)[0].strip();
            String[] dotParts = last.split("\\.");
            String tail = dotParts[dotParts.length - 1].replaceAll("[\\[\\]]", "");
            if (groupByCols.toUpperCase().contains(tail.toUpperCase())) continue;
            throw violation(resourceId,
                    "bare column '" + e + "' in SELECT list against app-table " + appTable
                            + " — leaks row data; remove or aggregate");
        }
    }

    private static String extractGroupByColumns(String sql) {
        var m = Pattern.compile("(?is)\\bGROUP\\s+BY\\s+(.*?)(\\bORDER\\s+BY|\\bHAVING|\\bUNION|$)").matcher(sql);
        return m.find() ? m.group(1) : "";
    }

    private static String[] splitTopLevelComma(String s) {
        // Split on commas at depth-0 parens.
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) {
                parts.add(s.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(s.substring(start));
        return parts.toArray(String[]::new);
    }

    private static IllegalStateException violation(String resourceId, String reason) {
        return new IllegalStateException(resourceId + ": " + reason);
    }

    private static List<Path> locateClasspathSqlFiles() throws IOException {
        // Walk every classpath root that has a `sql/` directory. Works for
        // both `src/main/resources` (under Gradle) and JAR-bundled
        // resources via the resource URL → file mapping. For JAR sources
        // we'd need ZipFileSystem, but the production CLI runs from the
        // build directory under Gradle so file-system access is sufficient.
        URL rootUrl = SqlGuard.class.getClassLoader().getResource("sql");
        if (rootUrl == null) {
            return List.of();
        }
        if (!"file".equals(rootUrl.getProtocol())) {
            return List.of();
        }
        Path root = Paths.get(rootUrl.getPath());
        try (Stream<Path> walk = Files.walk(root)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".sql"))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        }
    }

    public record ScanReport(int scannedCount, List<String> errors) {}
}
