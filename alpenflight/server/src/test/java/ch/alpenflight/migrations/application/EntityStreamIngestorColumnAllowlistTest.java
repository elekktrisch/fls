package ch.alpenflight.migrations.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.KnownMappers;
import ch.alpenflight.migration.bundle.Mapper;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;

class EntityStreamIngestorColumnAllowlistTest {

    @Test
    void known_mappers_all_pass_the_column_allowlist() {
        assertThat(new EntityStreamIngestor(KnownMappers.all()))
                .as("the shipped mapper registry clears the constructor's column allow-list — "
                        + "a violation would also refuse Spring boot in production, caught "
                        + "here at unit speed")
                .isNotNull();
    }

    @Test
    void mapper_with_quote_in_column_fails_construction() {
        Mapper offending = new FixedColumnsMapper(new String[] {"id", "username\""});
        assertThatThrownBy(() -> new EntityStreamIngestor(List.of(offending)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(offending.entityType().name())
                .hasMessageContaining("username\"")
                .hasMessageContaining("[A-Za-z0-9_]+ allowlist");
    }

    @Test
    void mapper_with_dash_in_column_fails_construction() {
        Mapper offending = new FixedColumnsMapper(new String[] {"id", "club-id"});
        assertThatThrownBy(() -> new EntityStreamIngestor(List.of(offending)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("club-id");
    }

    @Test
    void mapper_with_null_column_fails_construction() {
        Mapper offending = new FixedColumnsMapper(new String[] {"id", null});
        assertThatThrownBy(() -> new EntityStreamIngestor(List.of(offending)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static final class FixedColumnsMapper implements Mapper {

        private final String[] columns;

        FixedColumnsMapper(String[] columns) {
            this.columns = columns;
        }

        @Override
        public EntityType entityType() {
            return EntityType.COUNTRY;
        }

        @Override
        public String[] wireColumns() {
            return columns.clone();
        }

        @Override
        public List<EntityType> foreignKeyTargets() {
            return List.of();
        }

        @Override
        public void writeNdjson(ResultSet source, JsonGenerator target) {
            throw new UnsupportedOperationException("fixture");
        }

        @Override
        public void readEntity(JsonNode source, PreparedStatement target) throws SQLException {
            throw new UnsupportedOperationException("fixture");
        }
    }
}
