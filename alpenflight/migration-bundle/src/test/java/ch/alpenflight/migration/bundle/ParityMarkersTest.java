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
    void parityMarkersDoNotPickUpUnannotatedFields() {
        assertThat(ParityMarkers.sentinels(FixtureMapper.class))
                .as("only @ParitySentinel-marked fields belong in sentinels()")
                .containsExactly("operating_club_id");
        assertThat(ParityMarkers.ignored(FixtureMapper.class))
                .as("the unannotated PLAIN_FIELD must not leak into ignored()")
                .doesNotContain("plain_field");
    }

    private static final class FixtureMapper implements Mapper {
        @ParityIgnore
        static final String NOTES_CACHE = "notes_cache";

        @ParitySentinel
        static final String OPERATING_CLUB_ID = "operating_club_id";

        static final String PLAIN_FIELD = "plain_field";

        @Override public EntityType entityType() { return EntityType.COUNTRY; }
        @Override public String[] columns() {
            return new String[] { NOTES_CACHE, OPERATING_CLUB_ID, PLAIN_FIELD };
        }
        @Override public List<EntityType> foreignKeys() { return List.of(); }
        @Override public void writeNdjson(ResultSet source, JsonGenerator target)
                throws IOException { target.writeStartObject(); target.writeEndObject(); }
        @Override public void readEntity(JsonNode source, PreparedStatement target) { }
    }
}
