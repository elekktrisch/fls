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
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class FixtureTableNamingConventionTest {

    private static final Set<String> POSTGRES_CATALOG_AND_FLYWAY_TABLES = Set.of(
            "flyway_schema_history",
            "pg_indexes", "pg_index", "pg_attribute", "pg_constraint",
            "pg_namespace", "pg_class", "pg_type", "pg_proc", "pg_extension",
            "pg_tables", "pg_database", "pg_roles", "pg_stat_user_tables",
            "information_schema");

    private static final Set<String> SQL_KEYWORDS_AND_FIXTURE_QUERY_ALIASES = Set.of(
            "row", "rows",
            "t", "u", "c", "k", "col", "tc", "p", "rs", "kc", "tg",
            "a", "b", "i");

    private static final Set<String> ENGLISH_PROSE_WORDS_IN_ASSERTION_MESSAGES = Set.of(
            "legacy", "scratch", "source", "target", "above", "below");

    private static final Set<String> LEGACY_MSSQL_SOURCE_TABLES_MIGRATION_ITS_SEED_VERBATIM = Set.of(
            "planningdays",
            "planningdayassignments",
            "accountingrulefilters",
            "locations", "locationtypes", "inoutboundpoints", "clubs", "flights",
            "personflighttimecredits", "personflighttimecredittransactions", "deliveries",
            "deliveryitems", "articles",
            "personclub");

    private static final Set<String> ALLOW_LIST = Stream.of(
                    POSTGRES_CATALOG_AND_FLYWAY_TABLES,
                    SQL_KEYWORDS_AND_FIXTURE_QUERY_ALIASES,
                    ENGLISH_PROSE_WORDS_IN_ASSERTION_MESSAGES,
                    LEGACY_MSSQL_SOURCE_TABLES_MIGRATION_ITS_SEED_VERBATIM)
            .flatMap(Set::stream)
            .collect(Collectors.toUnmodifiableSet());

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

    private static boolean looksLikeJavaKeywordContext(String line, int matchStart, String identifier) {
        String trimmed = line.stripLeading();
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("import ")) {
            return true;
        }
        if (!startsUppercaseLikeAJpqlEntityName(identifier)) {
            return false;
        }
        String prefix = line.substring(0, matchStart).toLowerCase();
        return prefix.endsWith("from") || prefix.endsWith("join")
                || prefix.endsWith("from ") || prefix.endsWith("join ");
    }

    private static boolean startsUppercaseLikeAJpqlEntityName(String identifier) {
        return !identifier.isEmpty() && Character.isUpperCase(identifier.charAt(0));
    }
}
