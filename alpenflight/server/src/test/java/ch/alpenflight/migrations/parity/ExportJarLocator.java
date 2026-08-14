package ch.alpenflight.migrations.parity;

import java.nio.file.Files;
import java.nio.file.Path;

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
