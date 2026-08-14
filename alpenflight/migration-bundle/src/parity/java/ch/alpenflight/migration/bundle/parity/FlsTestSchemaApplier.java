package ch.alpenflight.migration.bundle.parity;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class FlsTestSchemaApplier {

    private static final Pattern GO_SEPARATOR = Pattern.compile("(?m)^\\s*GO\\s*$");
    private static final Pattern SKIP_BATCH = Pattern.compile(
            "(?i)^\\s*(USE\\s+\\[?(master|FLSTest)|CREATE\\s+DATABASE|ALTER\\s+DATABASE)");
    private static final Pattern SET_IDENTITY_INSERT_ON = Pattern.compile(
            "(?im)^\\s*SET\\s+IDENTITY_INSERT\\s+");

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
                    .sorted(scriptOrdering())
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
        return new Result(scripts.size(),
                counts.applied, counts.skipped, counts.failed);
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
                String trimmed = batch.strip();
                if (trimmed.isEmpty()) {
                    counts.skipped++;
                    continue;
                }
                if (SKIP_BATCH.matcher(trimmed).find()) {
                    counts.skipped++;
                    continue;
                }
                try {
                    statement.execute(trimmed);
                    counts.applied++;
                } catch (SQLException ignored) {
                    counts.failed++;
                }
            }
        }
    }

    private static Comparator<Path> scriptOrdering() {
        return Comparator
                .comparing((Path path) -> !path.getFileName().toString().startsWith("2 "))
                .thenComparing(path -> versionTuple(path.getFileName().toString()),
                        Comparator.comparing((int[] version) -> version[0])
                                .thenComparing(version -> version[1])
                                .thenComparing(version -> version[2])
                                .thenComparing(version -> version[3]))
                .thenComparing(path -> path.getFileName().toString());
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

    public record Result(int scriptsProcessed, int batchesApplied, int batchesSkipped, int batchesFailed) {
    }

    private static final class ApplyCounts {
        int applied;
        int skipped;
        int failed;
    }
}
