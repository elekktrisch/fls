package ch.alpenflight.legacyextract;

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

public final class SqlGuard {

    private static final int CHARACTERS_SCANNED_AFTER_SELECT_STAR_TO_FIND_ITS_FROM = 600;
    private static final String FROM_KEYWORD = "FROM ";

    private static final List<String> PII_BEARING_APP_TABLES = List.of(
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

    public static void assertSafe(String resourceId, String sql) {
        String stripped = stripComments(sql);
        rejectDdlDml(resourceId, stripped);
        rejectDetailedMode(resourceId, stripped);
        rejectSampledMode(resourceId, stripped);
        rejectSelectStarOutsideSystem(resourceId, stripped);
        rejectAppTableReference(resourceId, stripped);
    }

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
        if (!AGGREGATE_FUNCS.matcher(stripped).find()) {
            throw violation(resourceId,
                    "app-table " + appTable + " without aggregate function — would leak row data");
        }
        rejectBareColumnsWithAggregate(resourceId, stripped, appTable);
    }

    public static ScanReport scanClasspathResources() {
        List<String> errors = new ArrayList<>();
        int scanned = 0;
        try {
            List<Path> resourceFiles = locateClasspathSqlFiles();
            for (Path file : resourceFiles) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                String resourceId = file.toString();
                boolean isAggregateCategorySql = file.toString().replace('\\', '/').contains("/sql/aggregate/");
                try {
                    if (isAggregateCategorySql) {
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


    private static String stripComments(String sql) {
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
            String afterSelectStar = sql.substring(
                    idx, Math.min(sql.length(), idx + CHARACTERS_SCANNED_AFTER_SELECT_STAR_TO_FIND_ITS_FROM));
            int fromIdx = afterSelectStar.toUpperCase().indexOf(FROM_KEYWORD);
            if (fromIdx < 0) {
                throw violation(resourceId, "SELECT * with no FROM clause — name columns explicitly");
            }
            String afterFrom = afterSelectStar.substring(fromIdx + FROM_KEYWORD.length()).stripLeading();
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
        for (String name : PII_BEARING_APP_TABLES) {
            Pattern p = Pattern.compile(
                    "(?i)(\\bFROM|\\bJOIN)\\s+(\\[?dbo\\]?\\.)?\\[?" + Pattern.quote(name) + "\\]?\\b");
            if (p.matcher(sql).find()) {
                return name;
            }
        }
        return null;
    }

    private static void rejectBareColumnsWithAggregate(String resourceId, String sql, String appTable) {
        var selectListMatcher = Pattern.compile("(?is)\\bSELECT\\s+(.*?)\\bFROM\\b").matcher(sql);
        if (!selectListMatcher.find()) return;
        String selectList = selectListMatcher.group(1);

        String groupByColumns = extractGroupByColumns(sql);
        for (String selectListEntry : splitTopLevelComma(selectList)) {
            String expression = selectListEntry.strip();
            if (expression.isEmpty()) continue;
            if (AGGREGATE_FUNCS.matcher(expression).find()) continue;
            String expressionBeforeAlias = expression.split("\\s+AS\\s+", 2)[0].strip();
            String[] qualifierParts = expressionBeforeAlias.split("\\.");
            String unqualifiedColumn = qualifierParts[qualifierParts.length - 1].replaceAll("[\\[\\]]", "");
            if (groupByColumns.toUpperCase().contains(unqualifiedColumn.toUpperCase())) continue;
            throw violation(resourceId,
                    "bare column '" + expression + "' in SELECT list against app-table " + appTable
                            + " — leaks row data; remove or aggregate");
        }
    }

    private static String extractGroupByColumns(String sql) {
        var m = Pattern.compile("(?is)\\bGROUP\\s+BY\\s+(.*?)(\\bORDER\\s+BY|\\bHAVING|\\bUNION|$)").matcher(sql);
        return m.find() ? m.group(1) : "";
    }

    private static String[] splitTopLevelComma(String expressionList) {
        List<String> parts = new ArrayList<>();
        int parenthesisDepth = 0;
        int start = 0;
        for (int i = 0; i < expressionList.length(); i++) {
            char c = expressionList.charAt(i);
            if (c == '(') parenthesisDepth++;
            else if (c == ')') parenthesisDepth--;
            else if (c == ',' && parenthesisDepth == 0) {
                parts.add(expressionList.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(expressionList.substring(start));
        return parts.toArray(String[]::new);
    }

    private static IllegalStateException violation(String resourceId, String reason) {
        return new IllegalStateException(resourceId + ": " + reason);
    }

    private static List<Path> locateClasspathSqlFiles() throws IOException {
        URL rootUrl = SqlGuard.class.getClassLoader().getResource("sql");
        if (rootUrl == null) {
            return List.of();
        }
        if (!"file".equals(rootUrl.getProtocol())) {
            return List.of();
        }
        Path root;
        try {
            root = Paths.get(rootUrl.toURI());
        } catch (java.net.URISyntaxException e) {
            throw new IOException("invalid sql/ resource URL: " + rootUrl, e);
        }
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
