package ch.alpenflight.migration.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.ByteArrayOutputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CoercionsTest {

    @Test
    void writeRequiredTimestampFailsWithDiagnosticOnNullInsteadOfBareNpe() throws Exception {
        // J-0c T-12: a NULL legacy value for a NOT-NULL destination column used
        // to NPE inside value.toInstant() — getMessage() == null, so the
        // export's per-entity handler reported ": null", forcing an
        // entity-by-entity CI grind. The diagnostic now names the column.
        JsonGenerator gen = new JsonFactory().createGenerator(new ByteArrayOutputStream());
        gen.writeStartObject();
        assertThatThrownBy(
                () -> Coercions.writeRequiredTimestamp(gen, "created_on", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("created_on")
                .hasMessageContaining("NOT NULL");
    }

    @Test
    void writeRequiredTimestampEmitsIsoInstantWhenPresent() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (JsonGenerator gen = new JsonFactory().createGenerator(sink)) {
            gen.writeStartObject();
            Coercions.writeRequiredTimestamp(
                    gen, "created_on", Timestamp.from(Instant.parse("2016-06-07T22:53:46Z")));
            gen.writeEndObject();
        }
        assertThat(sink.toString())
                .contains("\"created_on\":\"2016-06-07T22:53:46Z\"");
    }

    @Test
    void deriveFanOutIdIsDeterministicAndAValidVersion5Uuid() {
        UUID legacyGuid = UUID.fromString("11111111-2222-3333-4444-555555555555");
        UUID clubA = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID clubB = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

        UUID derived = Coercions.deriveFanOutId(legacyGuid, clubA);

        assertThat(derived)
                .as("same (legacy_guid, legacy club) inputs derive the same id — "
                        + "re-ingest idempotency depends on byte-stability")
                .isEqualTo(Coercions.deriveFanOutId(legacyGuid, clubA));
        assertThat(derived.version()).as("RFC-4122 name-based v5").isEqualTo(5);
        assertThat(derived.variant()).as("RFC-4122 variant 0b10").isEqualTo(2);
        assertThat(Coercions.deriveFanOutId(legacyGuid, clubB))
                .as("the SAME shared legacy row fans out to a DISTINCT id per club")
                .isNotEqualTo(derived);
    }

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
