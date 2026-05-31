package ch.alpenflight.migration.bundle;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/**
 * Test-harness decorator that drops one column from a mapper's
 * {@link #columns()} while delegating everything else. Used by the
 * mutation-smoke self-test ({@code @Tag("parity-meta")}): wrapping a real
 * mapper and round-tripping it must make the diff fail and name the dropped
 * column — proving the sampled diff actually bites. Fails fast if the column
 * isn't one the delegate declares, so the self-test can't silently no-op.
 */
public final class ColumnDroppingMapper implements Mapper {

    private final Mapper delegate;
    private final String droppedColumn;
    private final String[] remainingColumns;

    public ColumnDroppingMapper(Mapper delegate, String droppedColumn) {
        this.delegate = delegate;
        this.droppedColumn = droppedColumn;
        String[] declared = delegate.columns();
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
    public String[] columns() {
        return remainingColumns.clone();
    }

    @Override
    public List<EntityType> foreignKeys() {
        return delegate.foreignKeys();
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
