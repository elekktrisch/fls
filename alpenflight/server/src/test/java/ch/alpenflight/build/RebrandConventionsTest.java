package ch.alpenflight.build;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Ratchet tests for the FLS → AlpenFlight technical rebrand (S-128). Each
 * test asserts a single post-rename invariant: no surviving legacy {@code ch.fls}
 * package declaration, no surviving {@code [fls-server]} log prefix, Spring
 * application name and Gradle coordinates pinned to the AlpenFlight identifiers.
 *
 * <p>Plain JUnit 5 + filesystem walk — no Spring context, no Docker, runs in
 * milliseconds via {@code ./gradlew check}. Catches regression-of-the-rebrand
 * as the source tree grows.
 */
class RebrandConventionsTest {

    private static final String FORBIDDEN_PACKAGE_PREFIX = "package ch." + "fls";
    private static final String FORBIDDEN_LOG_PREFIX = "[fls-server]";

    @Test
    void no_class_under_legacy_package_in_main_sources() throws IOException {
        List<String> offenders = findFilesContaining(
                locateModuleRoot().resolve("src/main/java"),
                FORBIDDEN_PACKAGE_PREFIX);
        assertThat(offenders)
                .as("no .java file under src/main/java may declare the legacy package prefix — rebrand to ch.alpenflight is complete")
                .isEmpty();
    }

    @Test
    void no_class_under_legacy_package_in_test_sources() throws IOException {
        List<String> offenders = findFilesContaining(
                locateModuleRoot().resolve("src/test/java"),
                FORBIDDEN_PACKAGE_PREFIX);
        assertThat(offenders)
                .as("no .java file under src/test/java may declare the legacy package prefix — rebrand to ch.alpenflight is complete")
                .isEmpty();
    }

    @Test
    void no_class_under_legacy_package_in_nullaway_demo_sources() throws IOException {
        Path demoRoot = locateModuleRoot().resolve("src/nullawayDemo/java");
        if (!Files.exists(demoRoot)) {
            return;
        }
        List<String> offenders = findFilesContaining(demoRoot, FORBIDDEN_PACKAGE_PREFIX);
        assertThat(offenders)
                .as("no .java file under src/nullawayDemo/java may declare the legacy package prefix")
                .isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void spring_application_name_is_alpenflight_server() throws IOException {
        try (var in = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            assertThat(in)
                    .as("application.yml must be on the test classpath")
                    .isNotNull();
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> spring = (Map<String, Object>) root.get("spring");
            Map<String, Object> application = (Map<String, Object>) spring.get("application");
            assertThat(application.get("name"))
                    .as("spring.application.name pinned to alpenflight-server")
                    .isEqualTo("alpenflight-server");
        }
    }

    @Test
    void no_fls_server_log_prefix_in_source_tree() throws IOException {
        Path moduleRoot = locateModuleRoot();
        List<String> offenders = new ArrayList<>();
        for (String relRoot : List.of("src/main/java", "src/test/java", "src/nullawayDemo/java")) {
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
                .as("no .java file may contain the literal `[fls-server]` log prefix outside of single-line comments")
                .isEmpty();
    }

    @Test
    void gradle_group_is_ch_alpenflight() throws IOException {
        Path buildFile = locateModuleRoot().resolve("build.gradle.kts");
        assertThat(buildFile).as("build.gradle.kts must exist at module root").exists();
        String content = Files.readString(buildFile, StandardCharsets.UTF_8);
        assertThat(content)
                .as("build.gradle.kts must declare group = \"ch.alpenflight\"")
                .contains("group = \"ch.alpenflight\"");
        assertThat(content)
                .as("build.gradle.kts must NOT declare the legacy group coordinate")
                .doesNotContain("group = \"ch." + "fls\"");
    }

    @Test
    void settings_root_project_name_is_alpenflight_server() throws IOException {
        Path settingsFile = locateModuleRoot().resolve("settings.gradle.kts");
        assertThat(settingsFile).as("settings.gradle.kts must exist at module root").exists();
        String content = Files.readString(settingsFile, StandardCharsets.UTF_8);
        assertThat(content)
                .as("settings.gradle.kts must declare rootProject.name = \"alpenflight-server\"")
                .contains("rootProject.name = \"alpenflight-server\"");
        assertThat(content)
                .as("settings.gradle.kts must NOT declare rootProject.name = \"fls-server\"")
                .doesNotContain("rootProject.name = \"fls-server\"");
    }

    /**
     * Strip {@code //} single-line comments before substring matching. Comments
     * are documentation, not executed code — a forbidden literal discussed in
     * a comment ("legacy used `[fls-server]` here") is not a violation.
     */
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

    /**
     * Exclude this test's own source file from the walks. The forbidden
     * literals ({@code package ch.fls}, {@code [fls-server]}) appear in the
     * test as the patterns it searches for; treating them as violations would
     * make the test reject itself.
     */
    private static boolean isOwnTestFile(Path p) {
        return p.getFileName().toString().equals("RebrandConventionsTest.java");
    }

    /**
     * Locate the {@code alpenflight/server/} module root by walking up from the
     * working directory until a {@code build.gradle.kts} sibling to
     * {@code src/} is found. Matches the resolution strategy in
     * {@code TenantCatalogYamlTest}.
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
