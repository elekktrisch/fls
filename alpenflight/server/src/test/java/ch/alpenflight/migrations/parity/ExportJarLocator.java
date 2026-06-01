package ch.alpenflight.migrations.parity;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves the built export-jar artifact from the {@code migration.tool.jar}
 * system property, which the server test task wires to the
 * {@code :migration-tool:shadowJar} output — never a hardcoded path, so the IT
 * always runs the freshly built jar. Fails loudly with the build command to run
 * when the property is unset or points at a missing file.
 */
public final class ExportJarLocator {

    static final String JAR_PROPERTY = "migration.tool.jar";

    private ExportJarLocator() { }

    public static Path locate() {
        String configured = System.getProperty(JAR_PROPERTY);
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "System property " + JAR_PROPERTY + " is not set — wire the server test "
                            + "task to the :migration-tool:shadowJar output "
                            + "(run ./gradlew :migration-tool:shadowJar).");
        }
        Path jar = Path.of(configured);
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException(
                    JAR_PROPERTY + " points at a missing file: " + jar
                            + " — run ./gradlew :migration-tool:shadowJar.");
        }
        return jar;
    }
}
