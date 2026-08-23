package ch.alpenflight.migration.bundle.parity;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class FlsTestSchemaApplier {

    private static final Pattern GO_SEPARATOR = Pattern.compile("(?m)^\\s*GO\\s*$");
    private static final Pattern BATCH_TARGETING_A_DATABASE_THE_FIXTURE_DOES_NOT_BUILD =
            Pattern.compile("(?i)^\\s*(USE\\s+\\[?master|CREATE\\s+DATABASE)");
    private static final Pattern LEADING_USE_OF_THE_DATABASE_THE_CONNECTION_IS_ALREADY_ON =
            Pattern.compile("(?i)\\A\\s*USE\\s+\\[?FLSTest\\]?\\s*;?\\s*");
    private static final Pattern ALTER_DATABASE_BATCH = Pattern.compile("(?i)^\\s*ALTER\\s+DATABASE");
    private static final Pattern COMPATIBILITY_LEVEL_ASSIGNMENT =
            Pattern.compile("(?i)COMPATIBILITY_LEVEL\\s*=\\s*(\\d+)");

    private FlsTestSchemaApplier() { }

    public static Result applyAll(Connection legacyConnection, Path flsTestRoot)
            throws IOException, SQLException {
        Path alterDirectory = flsTestRoot.resolve("2 alter");
        if (!Files.isDirectory(alterDirectory)) {
            throw new IllegalStateException(
                    "FLSTest schema not found at " + alterDirectory.toAbsolutePath()
                            + " — confirm cwd is the migration-bundle module or the repo root.");
        }
        List<Path> scripts;
        try (Stream<Path> entries = Files.list(alterDirectory)) {
            scripts = entries
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .sorted(semverAwareScriptOrdering())
                    .toList();
        }
        ApplyCounts counts = new ApplyCounts();
        legacyConnection.setAutoCommit(true);
        for (Path script : scripts) {
            applyScript(legacyConnection, script, counts);
        }
        applyScript(
                legacyConnection,
                flsTestRoot.resolve("3 insert").resolve("3 Insert Static Data.sql"),
                counts);
        int effectiveCompatibilityLevel =
                requireTheFixtureRunsAtTheCompatibilityLevelTheLegacyScriptsDeclare(
                        legacyConnection, counts.compatibilityLevelDeclaredByTheLegacyScripts);
        return new Result(scripts.size(),
                counts.applied, counts.skipped, counts.failed, effectiveCompatibilityLevel);
    }

    private static void applyScript(Connection connection, Path script, ApplyCounts counts)
            throws IOException, SQLException {
        if (!Files.isRegularFile(script)) {
            return;
        }
        String content = readScript(script);
        String[] batches = GO_SEPARATOR.split(content);
        try (Statement statement = connection.createStatement()) {
            for (String batch : batches) {
                String trimmed = LEADING_USE_OF_THE_DATABASE_THE_CONNECTION_IS_ALREADY_ON
                        .matcher(batch.strip())
                        .replaceFirst("")
                        .strip();
                if (trimmed.isEmpty()) {
                    counts.skipped++;
                    continue;
                }
                if (BATCH_TARGETING_A_DATABASE_THE_FIXTURE_DOES_NOT_BUILD.matcher(trimmed).find()) {
                    counts.skipped++;
                    continue;
                }
                var compatibilityLevel = COMPATIBILITY_LEVEL_ASSIGNMENT.matcher(trimmed);
                boolean setsTheCompatibilityLevel =
                        ALTER_DATABASE_BATCH.matcher(trimmed).find() && compatibilityLevel.find();
                if (ALTER_DATABASE_BATCH.matcher(trimmed).find() && !setsTheCompatibilityLevel) {
                    counts.skipped++;
                    continue;
                }
                try {
                    statement.execute(trimmed);
                    counts.applied++;
                    if (setsTheCompatibilityLevel) {
                        counts.compatibilityLevelDeclaredByTheLegacyScripts =
                                Integer.valueOf(compatibilityLevel.group(1));
                    }
                } catch (SQLException toleratedLegacyDialectQuirk) {
                    counts.failed++;
                }
            }
        }
    }

    private static int requireTheFixtureRunsAtTheCompatibilityLevelTheLegacyScriptsDeclare(
            Connection connection, Integer declared) throws SQLException {
        if (declared == null) {
            throw new IllegalStateException(
                    "No ALTER DATABASE ... SET COMPATIBILITY_LEVEL batch ran against the fixture "
                            + "database. The legacy scripts declare the level the production export "
                            + "compiles its T-SQL at, so a fixture that never applies it scores every "
                            + "producer SELECT at the container default and passes falsely.");
        }
        Integer effective = null;
        try (Statement statement = connection.createStatement();
                ResultSet levelOfTheCurrentDatabase = statement.executeQuery(
                        "SELECT compatibility_level FROM sys.databases WHERE database_id = DB_ID()")) {
            if (levelOfTheCurrentDatabase.next()) {
                effective = Integer.valueOf(levelOfTheCurrentDatabase.getInt(1));
            }
        }
        if (effective == null || effective.intValue() != declared.intValue()) {
            throw new IllegalStateException(
                    "Fixture database runs at compatibility level " + effective
                            + " but the legacy scripts declare " + declared
                            + ". A guard over this fixture would score T-SQL the production "
                            + "export never compiles.");
        }
        return effective.intValue();
    }

    private static Comparator<Path> semverAwareScriptOrdering() {
        return Comparator
                .comparing((Path path) -> !isDatabaseSettingsScript(path))
                .thenComparing(path -> versionTuple(path.getFileName().toString()),
                        Comparator.comparing((int[] version) -> version[0])
                                .thenComparing(version -> version[1])
                                .thenComparing(version -> version[2])
                                .thenComparing(version -> version[3]))
                .thenComparing(path -> path.getFileName().toString());
    }

    private static boolean isDatabaseSettingsScript(Path script) {
        return script.getFileName().toString().startsWith("2 ");
    }

    private static int[] versionTuple(String filename) {
        var matcher = Pattern.compile(
                "v(\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:p(\\d+))?",
                Pattern.CASE_INSENSITIVE).matcher(filename);
        if (!matcher.find()) {
            return new int[] {0, 0, 0, 0};
        }
        return new int[] {
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                matcher.group(3) != null ? Integer.parseInt(matcher.group(3)) : 0,
                matcher.group(4) != null ? Integer.parseInt(matcher.group(4)) : 0,
        };
    }

    private static String readScript(Path script) throws IOException {
        byte[] bytes = Files.readAllBytes(script);
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new String(bytes, 2, bytes.length - 2, Charset.forName("UTF-16LE"));
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new String(bytes, 2, bytes.length - 2, Charset.forName("UTF-16BE"));
        }
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public record Result(int scriptsProcessed, int batchesApplied, int batchesSkipped,
                         int batchesFailed, int compatibilityLevel) {
    }

    private static final class ApplyCounts {
        int applied;
        int skipped;
        int failed;
        Integer compatibilityLevelDeclaredByTheLegacyScripts;
    }
}
