package ch.fls;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Trivial smoke proving the Gradle `test` task discovers JUnit 5 tests. */
class SmokeUnitTest {

    @Test
    void junit5RunnerIsWired() {
        assertThat(1 + 1).isEqualTo(2);
    }
}
