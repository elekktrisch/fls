package ch.alpenflight.migrations.application;

import ch.alpenflight.migration.bundle.EntityPolicy;
import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.LegacyIdMapTables;
import ch.alpenflight.migration.bundle.Mapper;
import ch.alpenflight.migrations.domain.BundleIngestErrorCode;
import ch.alpenflight.migrations.domain.BundleIngestException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Per-bundle FK resolver. Translates legacy GUIDs carried in
 * {@code <Entity>.ndjson} rows to the new-stack UUIDs the destination
 * table FKs reference, by joining each {@link Mapper#foreignKeys()}
 * target against {@code legacy_id_map_<entity>} (seeded earlier in the
 * ingest by the {@code legacy_id_map/<entity>.pgcopy} tar entries +
 * {@link EntityStreamIngestor#seedClubLegacyIdMap}).
 *
 * <p>SYSTEM_GLOBAL_RESOLVE FKs (per {@link BundleManifest#entityPolicies()})
 * are <em>required</em> to resolve — the bundle pgcopy entries are the
 * sole source of the legacy-to-V2-seed translation, and a missing entry
 * indicates the producer dropped a row the consumer relied on. Surface
 * {@link BundleIngestErrorCode#BUNDLE_CROSS_TENANT_FK_LEAK}.
 *
 * <p>FULL_PORT FKs (e.g. {@code User.club_id} → {@code CLUB}) translate
 * via {@code legacy_id_map_club} which the orchestrator pre-seeds in
 * {@code seedClubLegacyIdMap}. A missing entry there leaves the field
 * untouched and the downstream INSERT surfaces the FK violation
 * naturally — the orchestrator's per-mapper save and audit trail then
 * carries the failure cleanly.
 *
 * <p>Column-name convention (vertical-slice mappers, S-187): the FK
 * column is {@code <target.name().toLowerCase()>_id} — e.g.
 * {@code club_id}, {@code country_id}, {@code language_id}. S-187a will
 * generalise to non-canonical names (e.g. {@code Aircraft.homebase_id}
 * → LOCATION).
 *
 * <p>Stateful — caches one prepared statement per target entity, scoped
 * to the ingest connection. Closed by {@link #close} when the per-entity
 * NDJSON drain completes.
 */
final class ForeignKeyResolver implements AutoCloseable {

    private final Connection connection;
    private final BundleManifest manifest;
    private final Map<EntityType, PreparedStatement> lookups = new EnumMap<>(EntityType.class);

    ForeignKeyResolver(Connection connection, BundleManifest manifest) {
        this.connection = connection;
        this.manifest = manifest;
    }

    /**
     * Walk the mapper's FK targets and rewrite each present legacy GUID
     * to the resolved new-stack UUID. Mutates {@code row} in place.
     */
    void rewriteForeignKeys(Mapper mapper, ObjectNode row) throws SQLException {
        for (EntityType target : mapper.foreignKeys()) {
            String field = conventionalForeignKeyField(target);
            JsonNode currentValue = row.get(field);
            if (currentValue == null || currentValue.isNull()) {
                continue;
            }
            UUID legacyGuid;
            try {
                legacyGuid = UUID.fromString(currentValue.asText());
            } catch (IllegalArgumentException badUuid) {
                throw new BundleIngestException(
                        BundleIngestErrorCode.NDJSON_PARSE_FAILED,
                        "FK field " + field + " on " + mapper.entityType()
                                + " is not a valid UUID: " + currentValue.asText(),
                        badUuid);
            }
            UUID resolved = lookupOrNull(target, legacyGuid);
            if (resolved != null) {
                row.put(field, resolved.toString());
            } else if (isSystemGlobalResolve(target)) {
                throw new BundleIngestException(
                        BundleIngestErrorCode.BUNDLE_CROSS_TENANT_FK_LEAK,
                        "FK " + field + " on " + mapper.entityType()
                                + " carries legacy guid " + legacyGuid
                                + " but legacy_id_map_" + target
                                + " has no resolution; the SYSTEM_GLOBAL bundle entry must "
                                + "enumerate every value the producer emitted");
            }
            // Else: FULL_PORT target with no mapping yet — let the FK
            // constraint surface the failure on INSERT.
        }
    }

    private @Nullable UUID lookupOrNull(EntityType target, UUID legacyGuid) throws SQLException {
        PreparedStatement ps = lookups.computeIfAbsent(target, this::prepareLookup);
        ps.setObject(1, legacyGuid);
        try (ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getObject(1, UUID.class) : null;
        }
    }

    private PreparedStatement prepareLookup(EntityType target) {
        try {
            return connection.prepareStatement(
                    "SELECT new_uuid FROM " + LegacyIdMapTables.temporaryTableName(target)
                            + " WHERE legacy_guid = ?");
        } catch (SQLException prep) {
            throw new RuntimeException(
                    "Failed to prepare FK lookup for " + target, prep);
        }
    }

    private boolean isSystemGlobalResolve(EntityType target) {
        EntityPolicy policy = manifest.entityPolicies().get(target);
        return policy != null
                && policy.portPolicy() == EntityPolicy.PortPolicy.SYSTEM_GLOBAL_RESOLVE;
    }

    private static String conventionalForeignKeyField(EntityType target) {
        return target.name().toLowerCase(Locale.ROOT) + "_id";
    }

    @Override
    public void close() {
        for (PreparedStatement ps : lookups.values()) {
            try {
                ps.close();
            } catch (SQLException ignored) {
                // Connection close-time will release whatever leaked.
            }
        }
        lookups.clear();
    }
}
