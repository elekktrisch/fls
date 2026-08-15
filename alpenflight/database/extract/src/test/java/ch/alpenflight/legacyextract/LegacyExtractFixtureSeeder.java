package ch.alpenflight.legacyextract;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

final class LegacyExtractFixtureSeeder {

    private static final Pattern GO_SEPARATOR = Pattern.compile("(?m)^\\s*GO\\s*$");
    private static final Pattern BATCH_TARGETING_A_DATABASE_ABSENT_FROM_THE_TEST_CONTAINER = Pattern.compile(
            "(?i)^\\s*(USE\\s+\\[?(master|FLSTest)|CREATE\\s+DATABASE|ALTER\\s+DATABASE)");
    private static final String DATABASE_LEVEL_SETTINGS_SCRIPT_PREFIX = "2 ";

    private LegacyExtractFixtureSeeder() {}

    static SeedResult applyAll(DataSource legacySqlServer, Path flsTestRoot) throws IOException {
        Path alterDir = flsTestRoot.resolve("2 alter");
        if (!Files.isDirectory(alterDir)) {
            throw new IllegalStateException("FLSTest fixture not found at " + alterDir.toAbsolutePath());
        }
        List<Path> scripts;
        try (Stream<Path> entries = Files.list(alterDir)) {
            scripts = entries
                    .filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .sorted(semverAwareLegacyInstallOrder())
                    .toList();
        }
        JdbcTemplate jdbc = new JdbcTemplate(legacySqlServer);
        int applied = 0;
        int skipped = 0;
        int failed = 0;
        for (Path script : scripts) {
            String content = readScript(script);
            String[] batches = GO_SEPARATOR.split(content);
            for (String batch : batches) {
                String trimmed = batch.strip();
                if (trimmed.isEmpty()) {
                    skipped++;
                    continue;
                }
                if (BATCH_TARGETING_A_DATABASE_ABSENT_FROM_THE_TEST_CONTAINER.matcher(trimmed).find()) {
                    skipped++;
                    continue;
                }
                try {
                    jdbc.execute(trimmed);
                    applied++;
                } catch (DataAccessException legacyDialectQuirkTheFixtureToleratesSkipping) {
                    failed++;
                }
            }
        }
        return new SeedResult(scripts.size(), applied, skipped, failed);
    }

    private static Comparator<Path> semverAwareLegacyInstallOrder() {
        return Comparator
                .comparing((Path script) -> isDatabaseLevelSettingsScript(script) ? 0 : 1)
                .thenComparing(script -> versionOf(script.getFileName().toString()),
                        Comparator.comparingInt(ScriptVersion::major)
                                .thenComparingInt(ScriptVersion::minor)
                                .thenComparingInt(ScriptVersion::patch)
                                .thenComparingInt(ScriptVersion::patchRevision))
                .thenComparing(script -> script.getFileName().toString());
    }

    private static boolean isDatabaseLevelSettingsScript(Path script) {
        return script.getFileName().toString().startsWith(DATABASE_LEVEL_SETTINGS_SCRIPT_PREFIX);
    }

    private record ScriptVersion(int major, int minor, int patch, int patchRevision) {}

    private static ScriptVersion versionOf(String filename) {
        var m = Pattern.compile("v(\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:p(\\d+))?", Pattern.CASE_INSENSITIVE).matcher(filename);
        if (!m.find()) return new ScriptVersion(0, 0, 0, 0);
        return new ScriptVersion(
                Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)),
                m.group(3) != null ? Integer.parseInt(m.group(3)) : 0,
                m.group(4) != null ? Integer.parseInt(m.group(4)) : 0);
    }

    private static String readScript(Path script) throws IOException {
        byte[] bytes = Files.readAllBytes(script);
        boolean hasUtf16LittleEndianByteOrderMark =
                bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE;
        if (hasUtf16LittleEndianByteOrderMark) {
            return new String(bytes, 2, bytes.length - 2, Charset.forName("UTF-16LE"));
        }
        boolean hasUtf16BigEndianByteOrderMark =
                bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF;
        if (hasUtf16BigEndianByteOrderMark) {
            return new String(bytes, 2, bytes.length - 2, Charset.forName("UTF-16BE"));
        }
        boolean hasUtf8ByteOrderMark = bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF;
        if (hasUtf8ByteOrderMark) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    record SeedResult(int scriptsProcessed, int batchesApplied, int batchesSkipped, int batchesFailed) {}
}
