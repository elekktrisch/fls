package ch.alpenflight.server.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Walks {@code alpenflight/server/src/test/java/**} and {@code .../test/resources/**}
 * for raw-JDBC SQL string literals against bare ({@code t_}-unprefixed) table
 * names. DB-free — runs on every {@code ./gradlew test} without a Postgres
 * container.
 *
 * <p>Production migrations under {@code db/migration/} are NOT scanned here:
 * their schema is covered by {@link TableNamingConventionTest}'s
 * {@code information_schema} sweep, and inline {@code COMMENT ON} text
 * routinely says things like "from article.article_number at booking" which
 * is descriptive English the regex would false-positive on.
 *
 * <p>Pattern matches {@code DELETE FROM}, {@code INSERT INTO}, {@code UPDATE},
 * {@code FROM}, {@code JOIN} followed by an identifier. The captured
 * identifier must either start with {@code t_} or sit in {@link #ALLOW_LIST}.
 *
 * <p>Makes "I missed a fixture in the t_-prefix sweep" structurally
 * impossible: a new IT that copies an unprefixed reference fails the build.
 */
class FixtureTableNamingConventionTest {

    /**
     * Identifiers that legitimately appear in our SQL fragments without a
     * {@code t_} prefix. Postgres catalog tables, Flyway's own table,
     * standard SQL clauses that share keywords with our verbs.
     */
    private static final Set<String> ALLOW_LIST = Set.of(
            // Flyway's own
            "flyway_schema_history",
            // Postgres catalog access
            "pg_indexes", "pg_index", "pg_attribute", "pg_constraint",
            "pg_namespace", "pg_class", "pg_type", "pg_proc", "pg_extension",
            "pg_tables", "pg_database", "pg_roles", "pg_stat_user_tables",
            // information_schema views
            "information_schema",
            // SQL keywords that follow FROM in non-table contexts
            "row", "rows",
            // CTE / subquery aliases used in fixtures
            "t", "u", "c", "k", "col", "tc", "p", "rs", "kc", "tg",
            // Aliases in JOIN context — false-positive guard
            "a", "b", "i",
            // English prose words that appear after FROM/JOIN/UPDATE in
            // assertion messages — none of these will ever be a table name.
            "legacy", "scratch", "source", "target", "above", "below",
            // Legacy MSSQL source-table name a producer-SELECT dedupe IT stands
            // up verbatim (J-6 T-11b PlanningDayProducerDedupeIT): the bound
            // PLANNING_DAY producer SELECT reads `FROM PlanningDays`, so the
            // staging table the IT seeds two duplicate rows into MUST carry that
            // exact unprefixed legacy name — it is NOT a new-stack t_ table.
            "planningdays",
            // J-6 T-16: the PLANNING_DAY_ASSIGNMENT producer SELECT reads `FROM
            // PlanningDayAssignments` (JOIN to PlanningDays + the kept-first remap);
            // the assignment-remap IT seeds that staging table verbatim. Legacy
            // source name, not a new-stack t_ table.
            "planningdayassignments",
            // J-8 T-10: the ACCOUNTING_RULE_FILTER producer SELECT reads `FROM
            // AccountingRuleFilters` (sort-indicator renumber + JSON_VALUE target
            // extraction); AccountingRuleFilterProducerDedupeIT seeds that staging
            // table verbatim. Legacy MSSQL source name, not a new-stack t_ table.
            "accountingrulefilters",
            // The LOCATION / INOUTBOUND_POINT fan-out producer SELECT reads `FROM
            // Locations` JOIN LocationTypes + the Clubs/Flights fan-out union;
            // LocationFanOutProducerSelectIT seeds those staging tables verbatim.
            // Legacy MSSQL source names, not new-stack t_ tables.
            "locations", "locationtypes", "inoutboundpoints", "clubs", "flights",
            // The PERSON_FLIGHT_TIME_CREDIT(_TRANSACTION) producer SELECTs read
            // `FROM PersonFlightTimeCredits` / `FROM PersonFlightTimeCreditTransactions`
            // (the IsCurrent dedupe) `LEFT JOIN Deliveries` (orphan-FK null-out);
            // PersonFlightTimeCreditProducerDedupeIT seeds those staging tables
            // verbatim. Legacy MSSQL source names, not new-stack t_ tables.
            "personflighttimecredits", "personflighttimecredittransactions", "deliveries",
            // The PERSON_CLUB producer SELECT reads `FROM PersonClub` (composite-PK
            // membership, MemberStateId orphan-nulled); the same IT's PersonClub
            // dedupe case seeds that staging table verbatim. Legacy MSSQL source
            // name, not a new-stack t_ table.
            "personclub");

    /**
     * Narrowed to SQL-context-only patterns: {@code DELETE FROM} /
     * {@code INSERT INTO} / {@code TRUNCATE} are unambiguous; {@code UPDATE}
     * requires a trailing {@code SET}; {@code FROM} / {@code JOIN} require
     * either a trailing SQL clause keyword OR a string-literal terminator
     * OR an optional alias before the clause keyword. Avoids matching
     * English prose like "from another tenant" or "update the value" while
     * still catching {@code "SELECT * FROM t_person_club"} (string ends at
     * the identifier) and {@code "FROM t_aircraft a JOIN t_club c ON …"}
     * (alias before next clause).
     */
    private static final Pattern FROM_LIKE = Pattern.compile(
            "(?i)\\b(?:"
                    + "(?:DELETE\\s+FROM|INSERT\\s+INTO|TRUNCATE(?:\\s+TABLE)?)\\s+([A-Za-z_][A-Za-z0-9_]*)"
                    + "|UPDATE\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+SET\\b"
                    + "|(?:FROM|JOIN)\\s+([A-Za-z_][A-Za-z0-9_]*)"
                    + "(?=\\s*(?:[\"';)]|$)"
                    + "|\\s+[A-Za-z_][A-Za-z0-9_]*\\s+(?:ON|WHERE|JOIN|GROUP|ORDER|LIMIT|HAVING|UNION|INNER|LEFT|RIGHT|FULL|CROSS|AS|;)"
                    + "|\\s+(?:WHERE|ON|JOIN|GROUP|ORDER|LIMIT|HAVING|UNION|INNER|LEFT|RIGHT|FULL|CROSS|AS))"
                    + ")");

    private static final Path TEST_ROOT = Path.of("src/test/java");
    private static final Path RESOURCE_ROOT = Path.of("src/test/resources");

    @Test
    void no_test_file_references_unprefixed_table() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path root : List.of(TEST_ROOT, RESOURCE_ROOT)) {
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                        .filter(FixtureTableNamingConventionTest::isScannable)
                        .forEach(path -> scan(path, offenders));
            }
        }
        assertThat(offenders)
                .as("Files contain bare-table-name SQL references (expected t_ prefix). "
                        + "Allow-list (Postgres catalog / Flyway): %s. "
                        + "Each entry below shows file:line.", ALLOW_LIST)
                .isEmpty();
    }

    private static boolean isScannable(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".java") || name.endsWith(".sql") || name.endsWith(".yml");
    }

    private static void scan(Path path, List<String> offenders) {
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + path, e);
        }
        Set<String> reported = new LinkedHashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher m = FROM_LIKE.matcher(line);
            while (m.find()) {
                String identifier = firstNonNullGroup(m);
                if (identifier == null) {
                    continue;
                }
                String lower = identifier.toLowerCase();
                if (lower.startsWith("t_") || ALLOW_LIST.contains(lower)) {
                    continue;
                }
                if (looksLikeJavaKeywordContext(line, m.start(), identifier)) {
                    continue;
                }
                String key = path + ":" + (i + 1) + " -> " + lower;
                if (reported.add(key)) {
                    offenders.add(key);
                }
            }
        }
    }

    private static String firstNonNullGroup(Matcher m) {
        for (int g = 1; g <= m.groupCount(); g++) {
            String v = m.group(g);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    /**
     * Java-language uses of FROM / UPDATE / JOIN that aren't SQL: import
     * statements, JPQL ({@code select … from User u}), Streams operations
     * etc. Filter by: the line is JPQL (contains {@code select} with a
     * capitalized entity ref) OR the match sits inside a Java comment line
     * starting with {@code //} or {@code *}.
     */
    private static boolean looksLikeJavaKeywordContext(String line, int matchStart, String identifier) {
        String trimmed = line.stripLeading();
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("import ")) {
            return true;
        }
        // JPQL: `from User u`, `from Aircraft a` — first letter of the
        // identifier after FROM is uppercase. Applied ONLY to bare
        // FROM/JOIN matches; INSERT INTO / DELETE FROM / UPDATE / TRUNCATE
        // are SQL-only and an upper-cased target there is a fixture typo,
        // not a JPQL reference.
        if (identifier.isEmpty() || !Character.isUpperCase(identifier.charAt(0))) {
            return false;
        }
        String prefix = line.substring(0, matchStart).toLowerCase();
        return prefix.endsWith("from") || prefix.endsWith("join")
                || prefix.endsWith("from ") || prefix.endsWith("join ");
    }
}
