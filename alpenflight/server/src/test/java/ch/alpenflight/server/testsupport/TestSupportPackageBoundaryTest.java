package ch.alpenflight.server.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class TestSupportPackageBoundaryTest {

    private static final String FORBIDDEN_IMPORT_PREFIX = "ch.alpenflight.server.testsupport";

    @Test
    void main_sources_must_not_reference_test_support_package() throws IOException {
        Path mainJava = locateModuleRoot().resolve("src/main/java");
        List<String> offenders = findFilesReferencing(mainJava, FORBIDDEN_IMPORT_PREFIX);
        assertThat(offenders)
                .as("no class under src/main/java may import %s.* — test-only surface", FORBIDDEN_IMPORT_PREFIX)
                .isEmpty();
    }

    private static List<String> findFilesReferencing(Path root, String needle) throws IOException {
        List<String> hits = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return hits;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> {
                        try {
                            String body = Files.readString(p, StandardCharsets.UTF_8);
                            if (body.contains(needle)) {
                                hits.add(root.relativize(p).toString());
                            }
                        } catch (IOException e) {
                            throw new RuntimeException("failed reading " + p, e);
                        }
                    });
        }
        return hits;
    }

    private static Path locateModuleRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        if (cwd.endsWith("server")) {
            return cwd;
        }
        Path nextServer = cwd.resolve("next").resolve("server");
        if (Files.isDirectory(nextServer)) {
            return nextServer;
        }
        return cwd;
    }
}
