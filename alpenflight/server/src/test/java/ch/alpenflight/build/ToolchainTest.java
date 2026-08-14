package ch.alpenflight.build;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ToolchainTest {

    @Test
    void javaSpecVersionIs25() {
        assertThat(System.getProperty("java.specification.version")).isEqualTo("25");
    }
}
