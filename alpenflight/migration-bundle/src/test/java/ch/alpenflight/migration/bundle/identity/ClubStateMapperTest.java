package ch.alpenflight.migration.bundle.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.migration.bundle.AbstractMapperContractTest;
import ch.alpenflight.migration.bundle.EntityType;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import net.datafaker.Faker;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ClubStateMapperTest extends AbstractMapperContractTest<ClubStateMapper> {

    private static final int LEGACY_CLUB_STATE_SYSTEM = 0;
    private static final int LEGACY_CLUB_STATE_ACTIVE = 1;
    private static final int LEGACY_CLUB_STATE_PASSIV = 2;
    private static final int LEGACY_CLUB_STATE_INACTIVE = 3;
    private static final int LEGACY_CLUB_STATE_ID_OUTSIDE_THE_ENUM = 99;

    private final ClubStateMapper mapper = new ClubStateMapper();

    @Override
    protected ClubStateMapper mapper() {
        return mapper;
    }

    @Override
    protected Map<String, Object> legacyRow(Faker faker) {
        return Map.of(
                "ClubStateId", faker.options().option(
                        LEGACY_CLUB_STATE_SYSTEM,
                        LEGACY_CLUB_STATE_ACTIVE,
                        LEGACY_CLUB_STATE_PASSIV,
                        LEGACY_CLUB_STATE_INACTIVE),
                "ClubStateName",
                faker.options().option("System", "Active", "Passiv", "Inactive"));
    }

    @Test
    void exposesClubStateEntityType() {
        assertThat(mapper.entityType()).isEqualTo(EntityType.CLUB_STATE);
    }

    @Test
    void hasNoForeignKeysAsSystemGlobalRef() {
        assertThat(mapper.foreignKeys()).isEmpty();
    }

    @Test
    void writeNdjsonRejectsLegacyIdOutsideTheKnownEnum() throws Exception {
        ResultSet rs = Mockito.mock(ResultSet.class);
        Mockito.when(rs.getInt("ClubStateId")).thenReturn(LEGACY_CLUB_STATE_ID_OUTSIDE_THE_ENUM);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (JsonGenerator generator = new JsonFactory().createGenerator(sink)) {
            assertThatThrownBy(() -> mapper.writeNdjson(rs, generator))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining(
                            "Legacy ClubStateId " + LEGACY_CLUB_STATE_ID_OUTSIDE_THE_ENUM);
        }
    }

    @Test
    void writeNdjsonEmitsTheV2CodeForEveryLegacyEnumValue() throws Exception {
        assertEmittedCode(LEGACY_CLUB_STATE_SYSTEM, "ACTIVE");
        assertEmittedCode(LEGACY_CLUB_STATE_ACTIVE, "ACTIVE");
        assertEmittedCode(LEGACY_CLUB_STATE_PASSIV, "CLOSED");
        assertEmittedCode(LEGACY_CLUB_STATE_INACTIVE, "SUSPENDED");
    }

    @Test
    void v2CodeForLegacyIdCoversEveryLegacyEnumValue() {
        assertThat(ClubStateMapper.v2CodeForLegacyId(LEGACY_CLUB_STATE_SYSTEM))
                .isEqualTo("ACTIVE");
        assertThat(ClubStateMapper.v2CodeForLegacyId(LEGACY_CLUB_STATE_ACTIVE))
                .isEqualTo("ACTIVE");
        assertThat(ClubStateMapper.v2CodeForLegacyId(LEGACY_CLUB_STATE_PASSIV))
                .isEqualTo("CLOSED");
        assertThat(ClubStateMapper.v2CodeForLegacyId(LEGACY_CLUB_STATE_INACTIVE))
                .isEqualTo("SUSPENDED");
        assertThat(ClubStateMapper.v2CodeForLegacyId(LEGACY_CLUB_STATE_ID_OUTSIDE_THE_ENUM))
                .isNull();
    }

    private void assertEmittedCode(int legacyId, String expectedCode) throws Exception {
        ResultSet rs = Mockito.mock(ResultSet.class);
        Mockito.when(rs.getInt("ClubStateId")).thenReturn(legacyId);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (JsonGenerator generator = new JsonFactory().createGenerator(sink)) {
            mapper.writeNdjson(rs, generator);
        }
        Map<String, Object> emitted = new ObjectMapper().readValue(
                sink.toByteArray(), Map.class);
        assertThat(emitted.get("code")).isEqualTo(expectedCode);
    }
}
