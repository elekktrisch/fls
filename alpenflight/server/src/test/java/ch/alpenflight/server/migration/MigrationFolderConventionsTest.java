package ch.alpenflight.server.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

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
    void identity_and_reference_migration_present() throws IOException {
        List<Path> migrations = listMigrations();
        assertThat(migrations)
                .as("S-012 ships a V<n>__identity_and_reference.sql migration (n >= 2)")
                .anyMatch(p -> {
                    String name = p.getFileName().toString();
                    return name.endsWith("__identity_and_reference.sql")
                            && name.matches("^V\\d+__identity_and_reference\\.sql$")
                            && !name.startsWith("V1__");
                });
    }

    @Test
    void identity_and_reference_migration_is_non_empty() throws IOException {
        List<Path> migrations = listMigrations();
        Path file = migrations.stream()
                .filter(p -> p.getFileName().toString().endsWith("__identity_and_reference.sql"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "identity_and_reference migration not found — see identity_and_reference_migration_present"));
        String content = Files.readString(file, StandardCharsets.UTF_8);
        String stripped = content.replaceAll("(?m)^--.*$", "").replaceAll("\\s+", "");
        assertThat(stripped)
                .as("identity_and_reference migration must contain at least one non-comment statement")
                .isNotEmpty();
    }

    @Test
    void flights_and_aircraft_migration_present() throws IOException {
        List<Path> migrations = listMigrations();
        assertThat(migrations)
                .as("S-013 ships a V<n>__flights_aircraft_locations.sql migration (n >= 3)")
                .anyMatch(p -> {
                    String name = p.getFileName().toString();
                    return name.endsWith("__flights_aircraft_locations.sql")
                            && name.matches("^V\\d+__flights_aircraft_locations\\.sql$")
                            && !name.startsWith("V1__")
                            && !name.startsWith("V2__");
                });
    }

    @Test
    void flights_baseline_migration_is_non_empty() throws IOException {
        List<Path> migrations = listMigrations();
        Path file = migrations.stream()
                .filter(p -> p.getFileName().toString().endsWith("__flights_aircraft_locations.sql"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "flights_aircraft_locations migration not found — see flights_and_aircraft_migration_present"));
        String content = Files.readString(file, StandardCharsets.UTF_8);
        String stripped = content.replaceAll("(?m)^--.*$", "").replaceAll("\\s+", "");
        assertThat(stripped)
                .as("flights_aircraft_locations migration must contain at least one non-comment statement")
                .isNotEmpty();
    }

    @Test
    void reservations_planning_accounting_migration_present() throws IOException {
        List<Path> migrations = listMigrations();
        assertThat(migrations)
                .as("S-014 ships a V<n>__reservations_planning_accounting.sql migration (n >= 4)")
                .anyMatch(p -> {
                    String name = p.getFileName().toString();
                    return name.endsWith("__reservations_planning_accounting.sql")
                            && name.matches("^V\\d+__reservations_planning_accounting\\.sql$")
                            && !name.startsWith("V1__")
                            && !name.startsWith("V2__")
                            && !name.startsWith("V3__");
                });
    }

    @Test
    void reservations_planning_accounting_migration_is_non_empty() throws IOException {
        List<Path> migrations = listMigrations();
        Path file = migrations.stream()
                .filter(p -> p.getFileName().toString().endsWith("__reservations_planning_accounting.sql"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "reservations_planning_accounting migration not found"
                                + " — see reservations_planning_accounting_migration_present"));
        String content = Files.readString(file, StandardCharsets.UTF_8);
        String stripped = content.replaceAll("(?m)^--.*$", "").replaceAll("\\s+", "");
        assertThat(stripped)
                .as("reservations_planning_accounting migration must contain at least one non-comment statement")
                .isNotEmpty();
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
    void no_business_logic_check_constraints_in_migrations() throws IOException {
        Pattern checkClause = Pattern.compile("\\bCHECK\\s*\\(", Pattern.CASE_INSENSITIVE);
        Pattern namedCheck = Pattern.compile(
                "CONSTRAINT\\s+(ck_\\w+)\\s+CHECK\\s*\\(",
                Pattern.CASE_INSENSITIVE);
        Pattern retainMarker = Pattern.compile(
                "COMMENT\\s+ON\\s+CONSTRAINT\\s+(ck_\\w+)\\s+ON\\s+\\w+\\s+IS\\s+'[^']*ADR\\s+0022\\s+retained[^']*'",
                Pattern.CASE_INSENSITIVE);

        var violations = new ArrayList<String>();
        for (Path m : listMigrations()) {
            String stripped = executableSqlOnly(Files.readString(m, StandardCharsets.UTF_8));

            Set<String> retained = new HashSet<>();
            Matcher rm = retainMarker.matcher(stripped);
            while (rm.find()) retained.add(rm.group(1).toLowerCase(java.util.Locale.ROOT));

            Set<Integer> allowedCheckStarts = new HashSet<>();
            Matcher nc = namedCheck.matcher(stripped);
            while (nc.find()) {
                if (retained.contains(nc.group(1).toLowerCase(java.util.Locale.ROOT))) {
                    int checkStart = stripped.indexOf("CHECK", nc.start());
                    if (checkStart < 0) {
                        checkStart = stripped.toLowerCase(java.util.Locale.ROOT).indexOf("check", nc.start());
                    }
                    allowedCheckStarts.add(checkStart);
                }
            }

            Matcher any = checkClause.matcher(stripped);
            while (any.find()) {
                if (allowedCheckStarts.contains(any.start())) continue;
                int line = (int) stripped.substring(0, any.start()).chars().filter(c -> c == '\n').count() + 1;
                violations.add(m.getFileName() + ":" + line);
            }
        }

        assertThat(violations)
                .as("CHECK constraints in migrations must drop (ADR 0022 directive 2) or carry a"
                        + " named CONSTRAINT + a co-located COMMENT ON CONSTRAINT ck_name ON table IS"
                        + " '%ADR 0022 retained: ...%' rationale. Anonymous CHECK clauses are always"
                        + " violations.")
                .isEmpty();
    }

    @Test
    void no_business_logic_check_constraints_test_catches_synthetic_violation() {
        Pattern checkClause = Pattern.compile("\\bCHECK\\s*\\(", Pattern.CASE_INSENSITIVE);
        String synthetic = "ALTER TABLE foo ADD CONSTRAINT ck_bar CHECK (x > 0);";
        String stripped = executableSqlOnly(synthetic);
        assertThat(checkClause.matcher(stripped).find())
                .as("CHECK scanner must catch a synthetic violation")
                .isTrue();
    }

    private static final Set<String> ROLE_PROVISIONING_PATTERNS = Set.of(
            "PASSWORD\\s*'",
            "\\bGRANT\\s",
            "\\bREVOKE\\s",
            "\\bALTER\\s+ROLE\\b",
            "\\bCREATE\\s+USER\\b",
            "\\bCREATE\\s+ROLE\\b");

    private static final String SECURITY_REVIEWED_MIGRATOR_ROLE_SPLIT_MIGRATION = "V54__";

    private static boolean isRoleProvisioningException(String filename, String patternText) {
        return filename.startsWith(SECURITY_REVIEWED_MIGRATOR_ROLE_SPLIT_MIGRATION)
                && ROLE_PROVISIONING_PATTERNS.contains(patternText);
    }

    @Test
    void no_forbidden_patterns_in_migrations() throws IOException {
        List<Pattern> forbidden = loadForbiddenPatterns();
        List<Path> migrations = listMigrations();
        var violations = new ArrayList<String>();
        for (Path m : migrations) {
            String stripped = executableSqlOnly(Files.readString(m, StandardCharsets.UTF_8));
            String filename = m.getFileName().toString();
            for (Pattern p : forbidden) {
                if (isRoleProvisioningException(filename, p.pattern())) {
                    continue;
                }
                if (p.matcher(stripped).find()) {
                    violations.add(filename + " matches forbidden pattern: " + p.pattern());
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

    private static String executableSqlOnly(String migrationBody) {
        return migrationBody.replaceAll("(?m)--[^\\n]*", "");
    }

    private static Path urlToPath(URL url) throws IOException {
        try {
            return Paths.get(url.toURI());
        } catch (URISyntaxException e) {
            throw new IOException("invalid resource URL: " + url, e);
        }
    }
}
