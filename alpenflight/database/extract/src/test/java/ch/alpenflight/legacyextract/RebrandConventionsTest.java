package ch.alpenflight.legacyextract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Ratchet tests for the FLS → AlpenFlight technical rebrand (S-128), extract-module
 * mirror of {@code ch.alpenflight.build.RebrandConventionsTest} in {@code alpenflight/server/}.
 * Asserts post-rename invariants for the legacy-extract Gradle subproject: no
 * surviving legacy {@code ch.fls} package declaration, no surviving
 * {@code [fls-extract]} log prefix, Gradle coordinates pinned to alpenflight.
 *
 * <p>Plain JUnit 5 + filesystem walk — no Spring, no Docker, runs in milliseconds
 * via {@code ./gradlew check}. Catches regression-of-the-rebrand as the
 * extract module's source tree grows.
 *
 * <p>Note: {@code FlsTest}-named helper methods in
 * {@code MetadataExtractorIntegrationTest} are NOT violations — they reference
 * the legacy {@code FLSTest} SQL Server database fixture (pinned by
 * {@code flsserver/database/FLSTest/}, owned by the legacy stack).
 */
class RebrandConventionsTest {

    private static final String FORBIDDEN_PACKAGE_PREFIX = "package ch." + "fls";
    private static final String FORBIDDEN_LOG_PREFIX = "[" + "fls-extract]";

    @Test
    void no_class_under_legacy_package_in_main_sources() throws IOException {
        List<String> offenders = findFilesContaining(
                locateModuleRoot().resolve("src/main/java"),
                FORBIDDEN_PACKAGE_PREFIX);
        assertThat(offenders)
                .as("no .java file under src/main/java may declare the legacy package prefix — rebrand to ch.alpenflight.legacyextract is complete")
                .isEmpty();
    }

    @Test
    void no_class_under_legacy_package_in_test_sources() throws IOException {
        List<String> offenders = findFilesContaining(
                locateModuleRoot().resolve("src/test/java"),
                FORBIDDEN_PACKAGE_PREFIX);
        assertThat(offenders)
                .as("no .java file under src/test/java may declare the legacy package prefix — rebrand to ch.alpenflight.legacyextract is complete")
                .isEmpty();
    }

    @Test
    void no_fls_extract_log_prefix_in_source_tree() throws IOException {
        Path moduleRoot = locateModuleRoot();
        List<String> offenders = new ArrayList<>();
        for (String relRoot : List.of("src/main/java", "src/test/java")) {
            Path root = moduleRoot.resolve(relRoot);
            if (!Files.exists(root)) continue;
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".java"))
                        .filter(p -> !isOwnTestFile(p))
                        .forEach(p -> {
                            try {
                                String stripped = stripLineComments(Files.readString(p, StandardCharsets.UTF_8));
                                if (stripped.contains(FORBIDDEN_LOG_PREFIX)) {
                                    offenders.add(moduleRoot.relativize(p).toString());
                                }
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        });
            }
        }
        assertThat(offenders)
                .as("no .java file may contain the literal `[fls-extract]` log prefix outside of single-line comments")
                .isEmpty();
    }

    @Test
    void gradle_group_is_ch_alpenflight_legacyextract() throws IOException {
        Path buildFile = locateModuleRoot().resolve("build.gradle.kts");
        assertThat(buildFile).as("build.gradle.kts must exist at module root").exists();
        String content = Files.readString(buildFile, StandardCharsets.UTF_8);
        assertThat(content)
                .as("build.gradle.kts must declare group = \"ch.alpenflight.legacyextract\"")
                .contains("group = \"ch.alpenflight.legacyextract\"");
        assertThat(content)
                .as("build.gradle.kts must NOT declare the legacy group coordinate")
                .doesNotContain("group = \"ch." + "fls.legacyextract\"");
    }

    @Test
    void settings_root_project_name_is_alpenflight_legacy_extract() throws IOException {
        Path settingsFile = locateModuleRoot().resolve("settings.gradle.kts");
        assertThat(settingsFile).as("settings.gradle.kts must exist at module root").exists();
        String content = Files.readString(settingsFile, StandardCharsets.UTF_8);
        assertThat(content)
                .as("settings.gradle.kts must declare rootProject.name = \"alpenflight-legacy-extract\"")
                .contains("rootProject.name = \"alpenflight-legacy-extract\"");
        assertThat(content)
                .as("settings.gradle.kts must NOT declare the legacy rootProject.name")
                .doesNotContain("rootProject.name = \"" + "fls-legacy-extract\"");
    }

    private static String stripLineComments(String source) {
        return source.replaceAll("(?m)//[^\\n]*", "");
    }

    private static List<String> findFilesContaining(Path root, String needle) throws IOException {
        if (!Files.exists(root)) {
            return List.of();
        }
        List<String> hits = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .filter(p -> !isOwnTestFile(p))
                    .forEach(p -> {
                        try {
                            String content = Files.readString(p, StandardCharsets.UTF_8);
                            if (content.contains(needle)) {
                                hits.add(root.relativize(p).toString());
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
        return hits;
    }

    private static boolean isOwnTestFile(Path p) {
        return p.getFileName().toString().equals("RebrandConventionsTest.java");
    }

    /**
     * Locate the {@code alpenflight/database/extract/} module root by walking up from
     * the working directory until a {@code build.gradle.kts} sibling to
     * {@code src/} is found.
     */
    private static Path locateModuleRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        Path probe = cwd;
        while (probe != null) {
            if (Files.exists(probe.resolve("build.gradle.kts"))
                    && Files.exists(probe.resolve("src"))) {
                return probe;
            }
            probe = probe.getParent();
        }
        throw new IllegalStateException(
                "module root (build.gradle.kts + src/) not found under any ancestor of " + cwd);
    }
}
