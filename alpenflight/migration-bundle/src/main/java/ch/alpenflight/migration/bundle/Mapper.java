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
     * Reference-lookup columns this mapper emits as the synthetic UUID
     * {@code new UUID(0, legacyIntId)} (via
     * {@link Coercions#legacyIntIdToUuidString}) and that the ingest pipeline
     * must resolve structurally to the real Flyway-seed PK by joining the named
     * seed table's {@code legacy_int_id} column.
     *
     * <p>Distinct from {@link #foreignKeys()}: those targets are bundle
     * {@link EntityType} entities resolved via {@code legacy_id_map_<entity>};
     * these targets are FLIGHT-group lookup tables in the V2/V3 seed, outside
     * the {@link EntityType} enum, with no per-bundle id map. Default empty so
     * existing mappers are unaffected — a mapper opts in by overriding.
     */
    default List<ReferenceLookup> referenceLookups() {
        return List.of();
    }

    /**
     * Non-canonical FK column declarations: each {@link ForeignKeyColumn} names a
     * destination column and the {@link EntityType} target it resolves through,
     * overriding the resolver's {@code <target>_id} naming convention for that
     * column.
     *
     * <p>Needed when a mapper names its FK columns off-convention (e.g.
     * {@code Aircraft.homebase_id} → LOCATION) or references the SAME target
     * through MULTIPLE columns (e.g. {@code managing_club_id} AND
     * {@code owner_club_id} both → CLUB) — neither expressible by the one-column-
     * per-target convention. The resolver iterates these declarations, then falls
     * back to the convention for any {@link #foreignKeys()} target NOT declared
     * here.
     *
     * <p>Orthogonal to {@link #foreignKeys()}: that list still enumerates the FK
     * targets (driving the ArchUnit ingest-order rule + the parity oracle); this
     * only overrides the column name(s) per target. Default empty so existing
     * mappers are unaffected — a mapper opts in by overriding.
     */
    default List<ForeignKeyColumn> foreignKeyColumns() {
        return List.of();
    }

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

    /**
     * Self-referencing FK columns the ingest must apply in a SECOND pass —
     * INSERT the row with these columns NULL, then {@code UPDATE} them once
     * EVERY row of this entity exists (S-141 two-pass). A self-FK cannot be
     * declared in {@link #foreignKeys()} (that would violate the ArchUnit
     * ingest-order invariant {@code source ordinal < target ordinal} — a
     * self-edge fails {@code <}), and the producer SELECTs all rows for the
     * club but emits them in an arbitrary intra-batch order, so a referencing
     * row (a glider) can stream BEFORE the row it points at (its tow). Binding
     * the column on the INSERT therefore FK-violates whenever the batch order
     * is unfavorable. Deferring it to an UPDATE after the full INSERT pass is
     * robust to ANY order.
     *
     * <p>The value carried in such a column is already the resolved
     * destination id (FLIGHT is non-fan-out FULL_PORT, so the legacy GUID is
     * preserved verbatim as the {@code id} — the self-FK target id == the
     * value the producer emitted), so the second pass needs no further
     * resolution; it just writes the value once the target row exists. A
     * null value (empty-guid sentinel / no tow) carries nothing forward — no
     * UPDATE is emitted, the column stays NULL.
     *
     * <p>Default empty so non-self-referencing mappers are unaffected — a
     * mapper opts in by overriding (only {@link EntityType#FLIGHT} does today,
     * for {@code tow_flight_id}).
     */
    default List<String> deferredSelfFkColumns() {
        return List.of();
    }
}
