package ch.alpenflight.migration.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class CoercionsTest {

    @Test
    void legacyIntIdToUuidStringPlacesValueInLeastSignificantBits() {
        assertThat(Coercions.legacyIntIdToUuidString(1))
                .as("Legacy INT widens into the LSB half of an otherwise zero UUID — "
                        + "the encoding is reversible by inspection (low-half = INT, "
                        + "high-half = 0).")
                .isEqualTo("00000000-0000-0000-0000-000000000001");
    }

    @Test
    void legacyIntIdToUuidStringIsDeterministicAcrossInvocations() {
        assertThat(Coercions.legacyIntIdToUuidString(42))
                .isEqualTo(Coercions.legacyIntIdToUuidString(42));
    }

    @Test
    void legacyIntIdToUuidStringRejectsNegativeInputs() {
        assertThatIllegalArgumentException()
                .as("Sign extension would alias the encoding (e.g. -1 → ffffffff in the "
                        + "low half), so negative legacy IDs are rejected at the boundary.")
                .isThrownBy(() -> Coercions.legacyIntIdToUuidString(-1))
                .withMessageContaining("non-negative");
    }

    @Test
    void legacyIntIdToUuidStringAcceptsZeroAsValidEncoding() {
        assertThat(Coercions.legacyIntIdToUuidString(0))
                .isEqualTo("00000000-0000-0000-0000-000000000000");
    }
}
