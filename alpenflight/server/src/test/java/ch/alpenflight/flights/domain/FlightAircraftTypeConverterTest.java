package ch.alpenflight.flights.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Sparse-enum converter for the discriminator: GLIDER=1, TOW=2, MOTOR=4.
 * Value 3 is deliberately skipped (legacy {@code GliderWithMotor} lives on
 * Aircraft, NOT Flight); reading 3 (or any other unknown legacy id) from
 * the DB must throw — silently accepting it would shift the bug surface.
 */
class FlightAircraftTypeConverterTest {

    private final FlightAircraftTypeConverter c = new FlightAircraftTypeConverter();

    @Test
    void writes_canonical_legacy_ids() {
        assertThat(c.convertToDatabaseColumn(FlightAircraftType.GLIDER)).isEqualTo((short) 1);
        assertThat(c.convertToDatabaseColumn(FlightAircraftType.TOW)).isEqualTo((short) 2);
        assertThat(c.convertToDatabaseColumn(FlightAircraftType.MOTOR)).isEqualTo((short) 4);
    }

    @Test
    void reads_canonical_legacy_ids() {
        assertThat(c.convertToEntityAttribute((short) 1)).isEqualTo(FlightAircraftType.GLIDER);
        assertThat(c.convertToEntityAttribute((short) 2)).isEqualTo(FlightAircraftType.TOW);
        assertThat(c.convertToEntityAttribute((short) 4)).isEqualTo(FlightAircraftType.MOTOR);
    }

    @ParameterizedTest
    @ValueSource(shorts = {0, 3, 5, 6, 100})
    void rejects_unknown_legacy_ids(short bogus) {
        assertThatThrownBy(() -> c.convertToEntityAttribute(bogus))
                .as("value 3 is the sparse-skip bug surface — silently accepting it would mask legacy drift")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown FlightAircraftType legacy id");
    }

    @Test
    void round_trips_null() {
        assertThat(c.convertToDatabaseColumn(null)).isNull();
        assertThat(c.convertToEntityAttribute(null)).isNull();
    }
}
