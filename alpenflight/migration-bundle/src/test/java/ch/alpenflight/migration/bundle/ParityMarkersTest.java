package ch.alpenflight.migration.bundle;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.identity.CountryMapper;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParityMarkersTest {

    @Test
    void picksUpParitySentinelOnSampleMapper() {
        assertThat(ParityMarkers.sentinels(CountryMapper.class))
                .containsExactly("iso2_code");
    }

    @Test
    void parityIgnoreCollectsAnnotatedStaticStringFields() {
        assertThat(ParityMarkers.ignored(FixtureMapper.class))
                .containsExactly("notes_cache");
    }

    @Test
    void ignoresUnannotatedFields() {
        assertThat(ParityMarkers.sentinels(FixtureMapper.class))
                .doesNotContain("plain_field");
    }

    private static final class FixtureMapper implements Mapper {
        @ParityIgnore
        static final String NOTES_CACHE = "notes_cache";
        static final String PLAIN_FIELD = "plain_field";

        @Override public EntityType entityType() { return EntityType.COUNTRY; }
        @Override public String[] columns() { return new String[] { NOTES_CACHE, PLAIN_FIELD }; }
        @Override public List<EntityType> foreignKeys() { return List.of(); }
        @Override public void writeNdjson(ResultSet source, JsonGenerator target)
                throws IOException { target.writeStartObject(); target.writeEndObject(); }
        @Override public void readEntity(JsonNode source, PreparedStatement target) { }
    }
}
