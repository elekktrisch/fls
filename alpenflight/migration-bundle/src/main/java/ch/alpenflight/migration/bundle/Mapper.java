package ch.alpenflight.migration.bundle;

/**
 * Per-entity bidirectional mapper — stateless singleton.
 *
 * <p>The export side (S-139) reads a JDBC {@link java.sql.ResultSet} and writes
 * one NDJSON line per row; the ingest side (S-141) reads one NDJSON line and
 * binds a {@code PreparedStatement} for {@code StatelessSession.insert}. The
 * two method signatures land in S-183 once Jackson + JDBC join the consumer
 * dependency closures; only the routing surface lives on the interface today.
 */
public interface Mapper {

    EntityType entityType();

    /**
     * New-schema column names in {@code PreparedStatement} parameter order.
     * Implementations must defensively copy — callers may mutate the returned
     * array. S-183 generalizes the per-mapper mutation test currently scoped to
     * {@code CountryMapperTest}.
     */
    String[] columns();
}
