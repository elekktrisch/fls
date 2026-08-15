package ch.alpenflight.migration.bundle;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

public final class ColumnDroppingMapper implements Mapper {

    private final Mapper delegate;
    private final String droppedColumn;
    private final String[] remainingColumns;

    public ColumnDroppingMapper(Mapper delegate, String droppedColumn) {
        this.delegate = delegate;
        this.droppedColumn = droppedColumn;
        String[] declared = delegate.wireColumns();
        if (Arrays.stream(declared).noneMatch(droppedColumn::equals)) {
            throw new IllegalArgumentException(
                    "Cannot drop column " + droppedColumn + " — " + delegate.entityType()
                            + " declares " + Arrays.toString(declared));
        }
        this.remainingColumns = Arrays.stream(declared)
                .filter(column -> !droppedColumn.equals(column))
                .toArray(String[]::new);
    }

    @Override
    public EntityType entityType() {
        return delegate.entityType();
    }

    @Override
    public String[] wireColumns() {
        return remainingColumns.clone();
    }

    @Override
    public List<EntityType> foreignKeyTargets() {
        return delegate.foreignKeyTargets();
    }

    @Override
    public void writeNdjson(ResultSet source, JsonGenerator target)
            throws SQLException, IOException {
        delegate.writeNdjson(source, target);
    }

    @Override
    public void readEntity(JsonNode source, PreparedStatement target) throws SQLException {
        delegate.readEntity(source, target);
    }

    public String droppedColumn() {
        return droppedColumn;
    }
}
