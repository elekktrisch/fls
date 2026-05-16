package ch.fls.server.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Static-asset tests over {@code src/main/resources/db/migration/} —
 * no DB needed, runs in every build, ~milliseconds.
 *
 * <p>Catches the contract-shape mistakes that would otherwise surface only at
 * production deploy: stray files, wrong naming, plain-text credentials in
 * migrations, missing baseline.
 */
class MigrationFolderConventionsTest {

    private static final Pattern MIGRATION_FILENAME =
            Pattern.compile("^(V\\d+(_\\d+)*|R)__[A-Za-z0-9_]+\\.sql$");

    @Test
    void db_migration_resource_folder_exists() {
        URL folder = getClass().getClassLoader().getResource("db/migration");
        assertThat(folder)
                .as("classpath resource db/migration must exist — canonical Flyway location per AC4")
                .isNotNull();
    }

    @Test
    void at_least_one_versioned_baseline_present() throws IOException {
        List<Path> migrations = listMigrations();
        assertThat(migrations)
                .as("at least one V<n>__*.sql file required (V1 baseline)")
                .anyMatch(p -> p.getFileName().toString().startsWith("V1__"));
    }

    @Test
    void every_file_matches_naming_convention() throws IOException {
        List<Path> migrations = listMigrations();
        for (Path m : migrations) {
            String name = m.getFileName().toString();
            assertThat(MIGRATION_FILENAME.matcher(name).matches())
                    .as("migration %s must match V<n>__<desc>.sql or R__<desc>.sql", name)
                    .isTrue();
        }
    }

    @Test
    void v1_baseline_is_non_empty() throws IOException {
        Path v1 = locateMigration("V1__baseline.sql");
        String content = Files.readString(v1, StandardCharsets.UTF_8);
        String stripped = content.replaceAll("(?m)^--.*$", "").replaceAll("\\s+", "");
        assertThat(stripped)
                .as("V1__baseline.sql must contain at least one non-comment, non-whitespace token")
                .isNotEmpty();
    }

    @Test
    void no_forbidden_patterns_in_migrations() throws IOException {
        List<Pattern> forbidden = loadForbiddenPatterns();
        List<Path> migrations = listMigrations();
        var violations = new ArrayList<String>();
        for (Path m : migrations) {
            String content = Files.readString(m, StandardCharsets.UTF_8);
            for (Pattern p : forbidden) {
                if (p.matcher(content).find()) {
                    violations.add(m.getFileName() + " matches forbidden pattern: " + p.pattern());
                }
            }
        }
        assertThat(violations)
                .as("migrations must not contain security-forbidden patterns (see forbidden-migration-patterns.txt)")
                .isEmpty();
    }

    @Test
    void dependency_graph_contains_flyway() throws ClassNotFoundException {
        Class.forName("org.flywaydb.core.Flyway");
        Class.forName("org.flywaydb.database.postgresql.PostgreSQLDatabaseType");
    }

    private List<Path> listMigrations() throws IOException {
        URL folderUrl = getClass().getClassLoader().getResource("db/migration");
        if (folderUrl == null) {
            return List.of();
        }
        Path folder = urlToPath(folderUrl);
        try (Stream<Path> walk = Files.walk(folder, 1)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .sorted()
                    .toList();
        }
    }

    private Path locateMigration(String filename) throws IOException {
        URL folderUrl = getClass().getClassLoader().getResource("db/migration");
        if (folderUrl == null) {
            throw new IOException("db/migration resource folder not found");
        }
        return urlToPath(folderUrl).resolve(filename);
    }

    private List<Pattern> loadForbiddenPatterns() throws IOException {
        URL url = getClass().getClassLoader().getResource("security/forbidden-migration-patterns.txt");
        assertThat(url)
                .as("forbidden-patterns fixture must exist at src/test/resources/security/")
                .isNotNull();
        List<Pattern> patterns = new ArrayList<>();
        try (var lines = Files.lines(urlToPath(url), StandardCharsets.UTF_8)) {
            lines.forEach(raw -> {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) return;
                patterns.add(Pattern.compile(line, Pattern.CASE_INSENSITIVE | Pattern.MULTILINE));
            });
        }
        return patterns;
    }

    /**
     * URL → URI → Path handles Windows correctly. {@code Paths.get(url.getPath())}
     * chokes on the leading slash in {@code /C:/Users/...} because {@code Paths.get}
     * parses string-side and sees the {@code :} at index 3 as illegal.
     */
    private static Path urlToPath(URL url) throws IOException {
        try {
            return Paths.get(url.toURI());
        } catch (URISyntaxException e) {
            throw new IOException("invalid resource URL: " + url, e);
        }
    }
}
