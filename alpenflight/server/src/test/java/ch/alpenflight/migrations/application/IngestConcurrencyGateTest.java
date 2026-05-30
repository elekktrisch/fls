package ch.alpenflight.migrations.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit guard for {@link IngestConcurrencyGate}. The integration-test
 * profile overrides the bean to {@code permits=100} so the per-upload
 * row-lock 409 path can be exercised in isolation — this test pins the
 * core semaphore semantics so a regression in the gate itself fails fast,
 * not buried inside an IT.
 */
class IngestConcurrencyGateTest {

    @Test
    void rejects_zero_or_negative_permits() {
        assertThatThrownBy(() -> new IngestConcurrencyGate(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("global-permits must be > 0");
        assertThatThrownBy(() -> new IngestConcurrencyGate(-3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("global-permits must be > 0");
    }

    @Test
    void single_permit_blocks_second_acquirer() {
        IngestConcurrencyGate gate = new IngestConcurrencyGate(1);
        assertThat(gate.tryAcquire()).isTrue();
        assertThat(gate.tryAcquire()).isFalse();
    }

    @Test
    void release_returns_permit_to_pool() {
        IngestConcurrencyGate gate = new IngestConcurrencyGate(1);
        assertThat(gate.tryAcquire()).isTrue();
        gate.release();
        assertThat(gate.tryAcquire())
                .as("released permit must be re-acquirable")
                .isTrue();
    }

    @Test
    void two_permits_admit_two_then_block() {
        IngestConcurrencyGate gate = new IngestConcurrencyGate(2);
        assertThat(gate.tryAcquire()).isTrue();
        assertThat(gate.tryAcquire()).isTrue();
        assertThat(gate.tryAcquire())
                .as("third tryAcquire after two-permit gate is exhausted")
                .isFalse();
    }
}
