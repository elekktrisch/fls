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

    private final ClubStateMapper mapper = new ClubStateMapper();

    @Override
    protected ClubStateMapper mapper() {
        return mapper;
    }

    @Override
    protected Map<String, Object> legacyRow(Faker faker) {
        return Map.of(
                "ClubStateId", faker.options().option(0, 1, 2, 3),
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
        Mockito.when(rs.getInt("ClubStateId")).thenReturn(99);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (JsonGenerator generator = new JsonFactory().createGenerator(sink)) {
            assertThatThrownBy(() -> mapper.writeNdjson(rs, generator))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("Legacy ClubStateId 99");
        }
    }

    @Test
    void writeNdjsonEmitsTheV2CodeForEveryLegacyEnumValue() throws Exception {
        assertEmittedCode(0, "ACTIVE");
        assertEmittedCode(1, "ACTIVE");
        assertEmittedCode(2, "CLOSED");
        assertEmittedCode(3, "SUSPENDED");
    }

    @Test
    void v2CodeForLegacyIdCoversEveryLegacyEnumValue() {
        assertThat(ClubStateMapper.v2CodeForLegacyId(0)).isEqualTo("ACTIVE");
        assertThat(ClubStateMapper.v2CodeForLegacyId(1)).isEqualTo("ACTIVE");
        assertThat(ClubStateMapper.v2CodeForLegacyId(2)).isEqualTo("CLOSED");
        assertThat(ClubStateMapper.v2CodeForLegacyId(3)).isEqualTo("SUSPENDED");
        assertThat(ClubStateMapper.v2CodeForLegacyId(99)).isNull();
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
