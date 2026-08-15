package ch.alpenflight.flights.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FlightAircraftTypeConverterTest {

    private final FlightAircraftTypeConverter converter = new FlightAircraftTypeConverter();

    @Test
    void writes_canonical_legacy_ids() {
        assertThat(converter.convertToDatabaseColumn(FlightAircraftType.GLIDER))
                .isEqualTo((short) 1);
        assertThat(converter.convertToDatabaseColumn(FlightAircraftType.TOW)).isEqualTo((short) 2);
        assertThat(converter.convertToDatabaseColumn(FlightAircraftType.MOTOR)).isEqualTo((short) 4);
    }

    @Test
    void reads_canonical_legacy_ids() {
        assertThat(converter.convertToEntityAttribute((short) 1))
                .isEqualTo(FlightAircraftType.GLIDER);
        assertThat(converter.convertToEntityAttribute((short) 2)).isEqualTo(FlightAircraftType.TOW);
        assertThat(converter.convertToEntityAttribute((short) 4))
                .isEqualTo(FlightAircraftType.MOTOR);
    }

    @ParameterizedTest
    @ValueSource(shorts = {0, 3, 5, 6, 100})
    void rejects_unknown_legacy_ids(short bogus) {
        assertThatThrownBy(() -> converter.convertToEntityAttribute(bogus))
                .as("value 3 is the sparse-skip bug surface — silently accepting it would mask legacy drift")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown FlightAircraftType legacy id");
    }

    @Test
    void round_trips_null() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }
}
