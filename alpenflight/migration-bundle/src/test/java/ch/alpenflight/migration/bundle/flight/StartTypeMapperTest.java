package ch.alpenflight.migration.bundle.flight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import ch.alpenflight.migration.bundle.AbstractMapperContractTest;
import ch.alpenflight.migration.bundle.EntityType;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.ByteArrayOutputStream;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;
import net.datafaker.Faker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StartTypeMapperTest extends AbstractMapperContractTest<StartTypeMapper> {

    private final StartTypeMapper mapper = new StartTypeMapper();

    @Override
    protected StartTypeMapper mapper() {
        return mapper;
    }

    @Override
    protected Map<String, Object> legacyRow(Faker faker) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("StartTypeId", 2);
        return row;
    }

    @Test
    void exposesStartTypeEntityType() {
        assertThat(mapper.entityType()).isEqualTo(EntityType.START_TYPE);
    }

    @Test
    void declaresNoForeignKeysSinceSystemGlobalResolveSkipsTopo() {
        assertThat(mapper.foreignKeys())
                .as("SYSTEM_GLOBAL_RESOLVE mappers resolve through V2 seeds, "
                        + "not through a per-bundle dependency declaration")
                .isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "1, AEROTOW",
        "2, WINCH_LAUNCH",
        "3, SELF_START",
        "4, EXTERNAL_START",
        "5, MOTOR",
    })
    void mapsLegacyEnumIdToCanonicalV2Code(int legacyId, String expectedCode) throws Exception {
        ResultSet source = mock(ResultSet.class);
        lenient().when(source.getInt(anyString())).thenReturn(legacyId);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonGenerator generator = new JsonFactory().createGenerator(out)) {
            mapper.writeNdjson(source, generator);
        }
        assertThat(out.toString()).contains("\"code\":\"" + expectedCode + "\"");
    }

    @Test
    void rejectsUnknownLegacyStartTypeIdWithStoryLevelDecisionMessage() throws Exception {
        ResultSet source = mock(ResultSet.class);
        lenient().when(source.getInt(anyString())).thenReturn(99);
        try (JsonGenerator generator = new JsonFactory().createGenerator(new ByteArrayOutputStream())) {
            assertThatThrownBy(() -> mapper.writeNdjson(source, generator))
                    .hasMessageContaining("StartTypeId 99")
                    .hasMessageContaining("story-level mapping decision");
        }
    }
}
