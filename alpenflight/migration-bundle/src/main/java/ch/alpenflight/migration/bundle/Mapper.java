package ch.alpenflight.migration.bundle;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Per-entity bidirectional mapper — stateless singleton.
 *
 * <p>Hot-path contract: {@link #writeNdjson} and {@link #readEntity} are called
 * once per row at >100K rows/sec. Implementations MUST NOT allocate per row
 * beyond Jackson + JDBC inherent. JMH on {@code FlightCrewMapper} (S-188)
 * enforces the budget; ArchUnit guards the prohibited APIs structurally.
 */
public interface Mapper {

    EntityType entityType();

    /**
     * New-schema column names in {@link PreparedStatement} parameter order
     * for {@link #readEntity}. Implementations must defensively copy —
     * callers may mutate the returned array.
     */
    String[] columns();

    /**
     * FK targets this mapper resolves through. Walked by the ArchUnit
     * ingest-order rule to assert every target's {@link EntityType} ordinal
     * is less than this entity's ordinal, and by the parity oracle (S-187)
     * to drive the cross-tenant FK sweep. SYSTEM_GLOBAL refs return an
     * empty list — see {@link EntityPolicy.PortPolicy#SYSTEM_GLOBAL_RESOLVE}.
     */
    List<EntityType> foreignKeys();

    /**
     * Export-side: stream one NDJSON line from the cursor's current row.
     * Caller has positioned the {@link ResultSet} on the row; this method
     * writes one {@code start-object} … {@code end-object} sequence to the
     * generator and returns. Caller flushes between rows.
     */
    void writeNdjson(ResultSet source, JsonGenerator target) throws SQLException, IOException;

    /**
     * Ingest-side: bind the {@link PreparedStatement} positional parameters
     * from the parsed JSON row. Parameter positions are 1-indexed and follow
     * {@link #columns()} order. Caller batches {@code addBatch()} / executes.
     */
    void readEntity(JsonNode source, PreparedStatement target) throws SQLException;
}
