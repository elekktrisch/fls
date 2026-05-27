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
 * Walks {@code alpenflight/server/src/test/java/**} and {@code .../resources/**}
 * for raw-JDBC SQL string literals against bare ({@code t_}-unprefixed) table
 * names. DB-free — runs on every {@code ./gradlew test} without a Postgres
 * container.
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
            "pg_namespace", "pg_class", "pg_type", "pg_proc",
            // information_schema views
            "information_schema",
            // SQL keywords that follow FROM in non-table contexts
            "row", "rows",
            // CTE / subquery aliases used in fixtures
            "t", "u", "c", "k", "col", "tc", "p", "rs", "kc", "tg",
            // Aliases in JOIN context — false-positive guard
            "a", "b", "i");

    private static final Pattern FROM_LIKE = Pattern.compile(
            "(?i)\\b(?:DELETE\\s+FROM|INSERT\\s+INTO|UPDATE|FROM|JOIN)\\s+([A-Za-z_][A-Za-z0-9_]*)");

    private static final Path TEST_ROOT = Path.of("src/test/java");
    private static final Path RESOURCE_ROOT = Path.of("src/test/resources");
    private static final Path MIGRATION_ROOT = Path.of("src/main/resources/db/migration");

    @Test
    void no_test_or_migration_file_references_unprefixed_table() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path root : List.of(TEST_ROOT, RESOURCE_ROOT, MIGRATION_ROOT)) {
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
                String identifier = m.group(1).toLowerCase();
                if (identifier.startsWith("t_") || ALLOW_LIST.contains(identifier)) {
                    continue;
                }
                if (looksLikeJavaKeywordContext(line, m.start())) {
                    continue;
                }
                String key = path + ":" + (i + 1) + " -> " + identifier;
                if (reported.add(key)) {
                    offenders.add(key);
                }
            }
        }
    }

    /**
     * Java-language uses of FROM / UPDATE / JOIN that aren't SQL: import
     * statements, JPQL ({@code select … from User u}), Streams operations
     * etc. Filter by: the line is JPQL (contains {@code select} with a
     * capitalized entity ref) OR the match sits inside a Java comment line
     * starting with {@code //} or {@code *}.
     */
    private static boolean looksLikeJavaKeywordContext(String line, int matchStart) {
        String trimmed = line.stripLeading();
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("import ")) {
            return true;
        }
        // JPQL: `from User u`, `from Aircraft a` — first letter of the
        // identifier after FROM is uppercase in JPQL.
        Matcher m = FROM_LIKE.matcher(line);
        while (m.find()) {
            if (m.start() == matchStart) {
                String id = m.group(1);
                if (!id.isEmpty() && Character.isUpperCase(id.charAt(0))) {
                    return true;
                }
            }
        }
        return false;
    }
}
