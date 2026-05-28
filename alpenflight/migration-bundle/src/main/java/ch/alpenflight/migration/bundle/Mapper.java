package ch.alpenflight.migration.bundle;

/**
 * Per-entity bidirectional mapper.
 *
 * <p>Mappers are stateless singletons. The export side (S-139) calls
 * {@code writeNdjson} with a JDBC {@link java.sql.ResultSet} positioned on a
 * legacy row and a Jackson {@code JsonGenerator} writing into the NDJSON entry
 * for that entity. The ingest side (S-141) calls {@code readEntity} with a
 * Jackson {@code JsonNode} for one NDJSON line and a {@code PreparedStatement}
 * bound to the matching {@code INSERT INTO t_<entity> ...} batch.
 *
 * <p><strong>Skeleton contract.</strong> Only the entity-routing surface
 * ({@link #entityType()} + {@link #columns()}) lives on the interface today.
 * The {@code writeNdjson} + {@code readEntity} method signatures land with
 * S-183 once Jackson + JDBC are in the dependency closure of the consumer
 * stories; their absence here is deferred-by-design.
 *
 * <p><strong>Column-list invariant.</strong> {@link #columns()} returns the
 * new-schema column names this mapper writes per row, in the order the
 * {@code PreparedStatement} expects. Shared by both directions so
 * {@code writeNdjson} + {@code readEntity} stay byte-aligned. Callers must not
 * mutate the returned array; implementations defensively copy.
 */
public interface Mapper {

    /** The entity this mapper serves. Drives the routing in S-139 / S-141 dispatch tables. */
    EntityType entityType();

    /** See class-level "Column-list invariant" — defensive-copied per the contract. */
    String[] columns();
}
