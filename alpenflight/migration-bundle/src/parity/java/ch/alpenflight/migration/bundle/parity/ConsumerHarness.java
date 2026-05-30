package ch.alpenflight.migration.bundle.parity;

import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.Mapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Consumer side of the round-trip: parse the tar.gz envelope, look up the
 * matching {@link Mapper} per entity, and invoke {@link Mapper#readEntity}
 * once per row to drive the {@link PreparedStatement} {@code INSERT} into
 * Postgres. Returns per-entity counts so the diff engine can compare
 * legacy-side vs new-side cardinalities.
 *
 * <p>SYSTEM_GLOBAL_RESOLVE entries (e.g. Country) are recorded in the
 * counts but skipped at insert — V2 already seeded the destination rows.
 */
public final class ConsumerHarness {

    private final Connection postgresConnection;
    private final Map<EntityType, Mapper> mappersByEntity;

    public ConsumerHarness(Connection postgresConnection, List<Mapper> mappers) {
        this.postgresConnection = postgresConnection;
        this.mappersByEntity = new LinkedHashMap<>();
        for (Mapper mapper : mappers) {
            mappersByEntity.put(mapper.entityType(), mapper);
        }
    }

    public Map<EntityType, Integer> ingest(BundleStream.Parsed parsedBundle)
            throws IOException, SQLException {
        Map<EntityType, Integer> rowCountByEntity = new LinkedHashMap<>();
        postgresConnection.setAutoCommit(false);
        try {
            for (Map.Entry<String, List<JsonNode>> entry
                    : parsedBundle.entityRowsByName().entrySet()) {
                EntityType entity = EntityType.valueOf(entry.getKey());
                Mapper mapper = mappersByEntity.get(entity);
                if (mapper == null) {
                    throw new IllegalStateException(
                            "Bundle carries entity " + entity + " but no mapper is registered "
                                    + "for it in this harness run. Add the mapper to "
                                    + "ParityOracleHarnessTest#mappers() or remove the entity "
                                    + "from the producer.");
                }
                rowCountByEntity.put(entity, ingestOne(entity, mapper, entry.getValue()));
            }
            postgresConnection.commit();
        } catch (SQLException | RuntimeException failure) {
            postgresConnection.rollback();
            throw failure;
        }
        return rowCountByEntity;
    }

    private int ingestOne(EntityType entity, Mapper mapper, List<JsonNode> rows)
            throws SQLException {
        if (!MapperLegacyBindings.isRegistered(entity)) {
            return rows.size();
        }
        String insert = MapperLegacyBindings.insertForConsumer(entity);
        if (insert.contains("SYSTEM_GLOBAL_RESOLVE")) {
            // Bundle byte stream is the only artefact; V2 already populated
            // the destination table.
            return rows.size();
        }
        try (PreparedStatement ps = postgresConnection.prepareStatement(insert)) {
            for (JsonNode row : rows) {
                mapper.readEntity(row, ps);
                ps.executeUpdate();
            }
        }
        return rows.size();
    }
}
