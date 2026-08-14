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
    private static final Pattern SKIP_BATCH = Pattern.compile(
            "(?i)^\\s*(USE\\s+\\[?(master|FLSTest)|CREATE\\s+DATABASE|ALTER\\s+DATABASE)");

    private LegacyExtractFixtureSeeder() {}

    static SeedResult applyAll(DataSource ds, Path flsTestRoot) throws IOException {
        Path alterDir = flsTestRoot.resolve("2 alter");
        if (!Files.isDirectory(alterDir)) {
            throw new IllegalStateException("FLSTest fixture not found at " + alterDir.toAbsolutePath());
        }
        List<Path> scripts;
        try (Stream<Path> entries = Files.list(alterDir)) {
            scripts = entries
                    .filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .sorted(scriptOrdering())
                    .toList();
        }
        JdbcTemplate jdbc = new JdbcTemplate(ds);
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
                if (SKIP_BATCH.matcher(trimmed).find()) {
                    skipped++;
                    continue;
                }
                try {
                    jdbc.execute(trimmed);
                    applied++;
                } catch (DataAccessException e) {
                    failed++;
                }
            }
        }
        return new SeedResult(scripts.size(), applied, skipped, failed);
    }

    private static Comparator<Path> scriptOrdering() {
        return Comparator
                .comparing((Path p) -> !p.getFileName().toString().startsWith("2 "))
                .thenComparing(p -> versionTuple(p.getFileName().toString()),
                        Comparator.comparing((int[] v) -> v[0])
                                .thenComparing(v -> v[1])
                                .thenComparing(v -> v[2])
                                .thenComparing(v -> v[3]))
                .thenComparing(p -> p.getFileName().toString());
    }

    private static int[] versionTuple(String filename) {
        var m = Pattern.compile("v(\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:p(\\d+))?", Pattern.CASE_INSENSITIVE).matcher(filename);
        if (!m.find()) return new int[] {0, 0, 0, 0};
        return new int[] {
                Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)),
                m.group(3) != null ? Integer.parseInt(m.group(3)) : 0,
                m.group(4) != null ? Integer.parseInt(m.group(4)) : 0,
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
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    record SeedResult(int scriptsProcessed, int batchesApplied, int batchesSkipped, int batchesFailed) {}
}
