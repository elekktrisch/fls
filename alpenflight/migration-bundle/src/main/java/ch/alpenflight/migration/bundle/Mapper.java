package ch.alpenflight.migration.bundle;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

public interface Mapper {

    EntityType entityType();

    String[] wireColumns();

    List<EntityType> foreignKeyTargets();

    default List<ReferenceLookup> referenceLookups() {
        return List.of();
    }

    default List<ForeignKeyColumn> foreignKeyColumns() {
        return List.of();
    }

    void writeNdjson(ResultSet source, JsonGenerator target) throws SQLException, IOException;

    void readEntity(JsonNode source, PreparedStatement target) throws SQLException;

    default List<String> deferredSelfFkColumns() {
        return List.of();
    }
}
