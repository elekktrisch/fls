package ch.alpenflight.migration.bundle;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

final class FakeMapper implements Mapper {

    private final EntityType entityType;
    private final String[] columns;
    private final List<EntityType> foreignKeys;
    int writeCalls;
    int readCalls;

    FakeMapper(EntityType entityType, String[] columns, List<EntityType> foreignKeys) {
        this.entityType = entityType;
        this.columns = columns;
        this.foreignKeys = foreignKeys;
    }

    @Override
    public EntityType entityType() {
        return entityType;
    }

    @Override
    public String[] wireColumns() {
        return columns.clone();
    }

    @Override
    public List<EntityType> foreignKeyTargets() {
        return foreignKeys;
    }

    @Override
    public void writeNdjson(ResultSet source, JsonGenerator target) {
        writeCalls++;
    }

    @Override
    public void readEntity(JsonNode source, PreparedStatement target) {
        readCalls++;
    }
}
