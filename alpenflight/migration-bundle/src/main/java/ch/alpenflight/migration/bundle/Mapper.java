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
 * <p>The concrete read+write method signatures land with the follow-up story
 * (jackson + JDBC types both come in then). The skeleton interface fixes the
 * cardinality (one class per entity, both directions on that class) + the
 * shared metadata each mapper must expose: which {@link EntityType} it serves,
 * its column list, its tenant-bypass FK list.
 */
public interface Mapper {

    /** The entity this mapper serves. Drives the routing in S-139 / S-141 dispatch tables. */
    EntityType entityType();

    /**
     * New-schema column names that this mapper writes per row, in the order
     * the {@code PreparedStatement} expects. Shared by both directions so
     * writeNdjson + readEntity stay byte-aligned.
     */
    String[] columns();
}
