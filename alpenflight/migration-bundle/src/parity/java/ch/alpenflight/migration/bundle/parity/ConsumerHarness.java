package ch.alpenflight.migration.bundle.parity;

import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.Mapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Consumer side of the round-trip — stand-in for S-141's ingest pipeline.
 * Two stages:
 *
 * <ol>
 *   <li><strong>Resolve SYSTEM_GLOBAL FKs.</strong> Populate the
 *       {@code legacy_guid → new_uuid} translation maps via
 *       {@link LegacyIdMapPopulator} by joining each SYSTEM_GLOBAL bundle
 *       entry against its V2-seeded destination on the lookup column.</li>
 *   <li><strong>Ingest FULL_PORT rows.</strong> For each row,
 *       {@link ForeignKeyRewriter} rewrites SYSTEM_GLOBAL FK columns from
 *       legacy GUID to new-stack UUID; {@code Mapper.readEntity} then binds
 *       the resolved row to the destination {@code INSERT}.</li>
 * </ol>
 *
 * <p>SYSTEM_GLOBAL bundle entries are recorded in the producer counts but
 * not inserted into the destination — V2 already seeded those rows.
 *
 * <p>FULL_PORT-to-FULL_PORT FKs (e.g. {@code User.club_id} → CLUB) flow
 * through unchanged: ADR 0019 legacy-GUID preservation makes the legacy
 * GUID identical to the destination row's PK. No rewrite needed.
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

    public IngestOutcome ingest(BundleStream.Parsed parsedBundle) throws SQLException {
        Map<EntityType, List<JsonNode>> systemGlobalBundleRows = new LinkedHashMap<>();
        Map<EntityType, List<JsonNode>> fullPortBundleRows = new LinkedHashMap<>();
        partitionBundleByPolicy(parsedBundle, systemGlobalBundleRows, fullPortBundleRows);

        LegacyIdMapPopulator.Maps legacyIdMaps =
                LegacyIdMapPopulator.populate(postgresConnection, systemGlobalBundleRows);

        Map<EntityType, Integer> rowCountByEntity = new LinkedHashMap<>();
        for (Map.Entry<EntityType, List<JsonNode>> entry : systemGlobalBundleRows.entrySet()) {
            rowCountByEntity.put(entry.getKey(), entry.getValue().size());
        }

        postgresConnection.setAutoCommit(false);
        try {
            for (Map.Entry<EntityType, List<JsonNode>> entry : fullPortBundleRows.entrySet()) {
                EntityType entity = entry.getKey();
                Mapper mapper = mappersByEntity.get(entity);
                rowCountByEntity.put(entity,
                        ingestOne(entity, mapper, entry.getValue(), legacyIdMaps));
            }
            postgresConnection.commit();
        } catch (SQLException | RuntimeException failure) {
            postgresConnection.rollback();
            throw failure;
        }
        return new IngestOutcome(rowCountByEntity, legacyIdMaps);
    }

    private void partitionBundleByPolicy(
            BundleStream.Parsed parsedBundle,
            Map<EntityType, List<JsonNode>> systemGlobal,
            Map<EntityType, List<JsonNode>> fullPort) {
        for (Map.Entry<String, List<JsonNode>> entry
                : parsedBundle.entityRowsByName().entrySet()) {
            EntityType entity = EntityType.valueOf(entry.getKey());
            if (!mappersByEntity.containsKey(entity)) {
                throw new IllegalStateException(
                        "Bundle carries entity " + entity + " but no mapper is registered "
                                + "for it in this harness run.");
            }
            if (MapperLegacyBindings.portPolicy(entity)
                    == MapperLegacyBindings.PortPolicy.SYSTEM_GLOBAL) {
                systemGlobal.put(entity, entry.getValue());
            } else {
                fullPort.put(entity, entry.getValue());
            }
        }
    }

    private int ingestOne(
            EntityType entity,
            Mapper mapper,
            List<JsonNode> rows,
            LegacyIdMapPopulator.Maps legacyIdMaps) throws SQLException {
        String insert = MapperLegacyBindings.insertForConsumer(entity);
        try (PreparedStatement ps = postgresConnection.prepareStatement(insert)) {
            for (JsonNode row : rows) {
                if (row instanceof ObjectNode mutableRow) {
                    ForeignKeyRewriter.rewrite(mapper, mutableRow, legacyIdMaps);
                }
                mapper.readEntity(row, ps);
                ps.executeUpdate();
            }
        }
        return rows.size();
    }

    public record IngestOutcome(
            Map<EntityType, Integer> rowCountByEntity,
            LegacyIdMapPopulator.Maps legacyIdMaps) {
    }
}
