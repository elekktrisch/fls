package ch.alpenflight.migrations.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExportJarLocatorTest {

    private String previousValue;

    @BeforeEach
    void snapshotProperty() {
        previousValue = System.getProperty(ExportJarLocator.JAR_PROPERTY);
    }

    @AfterEach
    void restoreProperty() {
        if (previousValue == null) {
            System.clearProperty(ExportJarLocator.JAR_PROPERTY);
        } else {
            System.setProperty(ExportJarLocator.JAR_PROPERTY, previousValue);
        }
    }

    @Test
    void returnsThePathWhenThePropertyPointsAtABuiltJar(@TempDir Path dir) throws Exception {
        Path jar = Files.createFile(dir.resolve("export.jar"));
        System.setProperty(ExportJarLocator.JAR_PROPERTY, jar.toString());

        assertThat(ExportJarLocator.locate()).isEqualTo(jar);
    }

    @Test
    void failsLoudlyWhenThePropertyIsUnset() {
        System.clearProperty(ExportJarLocator.JAR_PROPERTY);

        assertThatThrownBy(ExportJarLocator::locate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ExportJarLocator.JAR_PROPERTY)
                .hasMessageContaining("shadowJar");
    }

    @Test
    void failsWhenThePropertyPointsAtAMissingFile(@TempDir Path dir) {
        System.setProperty(ExportJarLocator.JAR_PROPERTY, dir.resolve("absent.jar").toString());

        assertThatThrownBy(ExportJarLocator::locate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing file");
    }
}
