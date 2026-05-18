package ch.alpenflight.platform.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class ClubIdTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new TypedIdJacksonModule())
            .build();

    @Test
    void encodes_to_clb_prefix_plus_dashed_uuid() {
        ClubId id = ClubId.of(UUID.fromString("019e30c3-2c00-7001-8000-000000000001"));

        assertThat(id.toExternal())
                .isEqualTo("clb-019e30c3-2c00-7001-8000-000000000001")
                .startsWith("clb-")
                .matches("^clb-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
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
        assertThatThrownBy(() -> ClubId.parse("019e30c3-2c00-7001-8000-0000000000aa"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(ClubId.PREFIX);
    }

    @Test
    void rejects_malformed_payload() {
        assertThatThrownBy(() -> ClubId.parse("clb-not-a-uuid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a valid UUID");
    }

    @Test
    void rejects_truncated_payload() {
        // Drop the last 12 hex characters — UUID.fromString refuses anything
        // that's not exactly the canonical 8-4-4-4-12 shape.
        assertThatThrownBy(() -> ClubId.parse("clb-019e30c3-2c00-7001-8000-"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a valid UUID");
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
                .isEqualTo("\"clb-019e30c3-2c00-7001-8000-000000000001\"");
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
