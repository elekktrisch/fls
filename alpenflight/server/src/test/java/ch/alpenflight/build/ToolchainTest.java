package ch.alpenflight.build;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Cheap regression: the build runs on the pinned JDK. */
class ToolchainTest {

    @Test
    void javaSpecVersionIs25() {
        assertThat(System.getProperty("java.specification.version")).isEqualTo("25");
    }
}
