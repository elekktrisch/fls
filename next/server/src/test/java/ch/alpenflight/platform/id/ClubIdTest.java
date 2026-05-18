package ch.alpenflight.platform.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClubIdTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void encodes_to_clb_prefix_plus_26_chars() {
        ClubId id = ClubId.of(UUID.fromString("019e30c3-2c00-7001-8000-000000000001"));

        assertThat(id.toExternal())
                .startsWith("clb_")
                .hasSize("clb_".length() + 26)
                .matches("^clb_[0-9a-z]{26}$");
    }

    @Test
    void round_trip_preserves_uuid() {
        UUID raw = UUID.fromString("019e30c3-2c00-7001-8000-0000000000aa");
        ClubId parsed = ClubId.parse(ClubId.of(raw).toExternal());

        assertThat(parsed.value()).isEqualTo(raw);
    }

    @Test
    void random_uuids_round_trip() {
        for (int i = 0; i < 100; i++) {
            UUID raw = UUID.randomUUID();
            assertThat(ClubId.parse(ClubId.of(raw).toExternal()).value()).isEqualTo(raw);
        }
    }

    @Test
    void rejects_missing_prefix() {
        assertThatThrownBy(() -> ClubId.parse("019e30c32c00700180000000000000aa"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(ClubId.PREFIX);
    }

    @Test
    void rejects_wrong_length_payload() {
        assertThatThrownBy(() -> ClubId.parse("clb_short"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("26");
    }

    @Test
    void rejects_illegal_character() {
        assertThatThrownBy(() -> ClubId.parse("clb_!!!!!!!!!!!!!!!!!!!!!!!!!!!"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_null_value_in_constructor() {
        assertThatThrownBy(() -> ClubId.of(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void jackson_serialises_to_external_string() throws Exception {
        ClubId id = ClubId.of(UUID.fromString("019e30c3-2c00-7001-8000-000000000001"));

        assertThat(MAPPER.writeValueAsString(id))
                .startsWith("\"clb_")
                .endsWith("\"");
    }

    @Test
    void jackson_deserialises_from_external_string() throws Exception {
        UUID raw = UUID.fromString("019e30c3-2c00-7001-8000-000000000001");
        String json = "\"" + ClubId.of(raw).toExternal() + "\"";

        ClubId parsed = MAPPER.readValue(json, ClubId.class);

        assertThat(parsed.value()).isEqualTo(raw);
    }

    @Test
    void toString_returns_external_form() {
        ClubId id = ClubId.of(UUID.fromString("019e30c3-2c00-7001-8000-000000000001"));

        assertThat(id.toString()).isEqualTo(id.toExternal());
    }
}
