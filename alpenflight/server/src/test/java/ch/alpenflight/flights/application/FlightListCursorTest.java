package ch.alpenflight.flights.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FlightListCursorTest {

    private static final UUID ID =
            UUID.fromString("019e30c3-2c00-7001-8000-0000000000ff");

    @Test
    void encode_decode_roundtrip() {
        FlightListCursor c = new FlightListCursor(LocalDate.of(2026, 5, 24), ID);
        FlightListCursor back = FlightListCursor.decode(c.encode());
        assertThat(back).isEqualTo(c);
    }

    @Test
    void encode_decode_null_date_roundtrip() {
        FlightListCursor c = new FlightListCursor(null, ID);
        FlightListCursor back = FlightListCursor.decode(c.encode());
        assertThat(back).isEqualTo(c);
    }

    @Test
    void decode_rejects_blank() {
        assertThatThrownBy(() -> FlightListCursor.decode(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decode_rejects_garbage() {
        assertThatThrownBy(() -> FlightListCursor.decode("not-base64-!!"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
