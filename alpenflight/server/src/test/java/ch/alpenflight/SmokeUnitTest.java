package ch.alpenflight;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SmokeUnitTest {

    @Test
    void junit5RunnerIsWired() {
        assertThat(1 + 1).isEqualTo(2);
    }
}
