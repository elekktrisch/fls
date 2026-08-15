package ch.alpenflight.migrations.application;

import ch.alpenflight.migration.bundle.EntityPolicy;
import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.ForeignKeyColumn;
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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

final class ForeignKeyResolver implements AutoCloseable {

    private static final String REFERENCER_CLUB_FIELD = "club_id";

    private final Connection connection;
    private final BundleManifest manifest;
    private final Map<EntityType, PreparedStatement> lookups = new EnumMap<>(EntityType.class);
    private final Map<EntityType, PreparedStatement> compositeLookups =
            new EnumMap<>(EntityType.class);

    ForeignKeyResolver(Connection connection, BundleManifest manifest) {
        this.connection = connection;
        this.manifest = manifest;
    }

    void rewriteForeignKeys(Mapper mapper, ObjectNode row) throws SQLException {
        Map<String, UUID> legacyDisambiguators = snapshotDisambiguators(mapper, row);
        for (ForeignKeyBinding binding : foreignKeyBindings(mapper)) {
            EntityType target = binding.target();
            String field = binding.column();
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
            if (target.fansOut()) {
                resolveFanOutForeignKey(mapper, row, target, field,
                        binding.disambiguatorColumn(), legacyDisambiguators, legacyGuid);
                continue;
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
        }
    }

    private void resolveFanOutForeignKey(
            Mapper mapper,
            ObjectNode row,
            EntityType target,
            String field,
            @Nullable String disambiguatorColumn,
            Map<String, UUID> legacyDisambiguators,
            UUID legacyGuid)
            throws SQLException {
        String clubField =
                disambiguatorColumn != null ? disambiguatorColumn : REFERENCER_CLUB_FIELD;
        UUID referencerClubId = legacyDisambiguators.get(clubField);
        if (referencerClubId == null) {
            throw new BundleIngestException(
                    BundleIngestErrorCode.BUNDLE_CROSS_TENANT_FK_LEAK,
                    "FK " + field + " on " + mapper.entityType() + " targets fan-out "
                            + target + " but the referencer row carries no own "
                            + clubField + " to disambiguate which replica it means");
        }
        UUID resolved = lookupCompositeOrNull(target, legacyGuid, referencerClubId);
        if (resolved == null) {
            throw new BundleIngestException(
                    BundleIngestErrorCode.BUNDLE_CROSS_TENANT_FK_LEAK,
                    "FK " + field + " on " + mapper.entityType()
                            + " carries legacy guid " + legacyGuid + " for club "
                            + referencerClubId + " but legacy_id_map_" + target
                            + " has no replica for that (legacy_guid, club_id) pair; the "
                            + "fan-out producer must emit one id-map row per referencing club");
        }
        row.put(field, resolved.toString());
    }

    void rewriteSelfId(EntityType selfType, String idField, ObjectNode row) throws SQLException {
        JsonNode idValue = row.get(idField);
        if (idValue == null || idValue.isNull()) {
            throw new BundleIngestException(
                    BundleIngestErrorCode.NDJSON_PARSE_FAILED,
                    selfType + " row is missing its " + idField + " identity");
        }
        UUID legacyId;
        try {
            legacyId = UUID.fromString(idValue.asText());
        } catch (IllegalArgumentException badUuid) {
            throw new BundleIngestException(
                    BundleIngestErrorCode.NDJSON_PARSE_FAILED,
                    selfType + " " + idField + " is not a valid UUID: " + idValue.asText(),
                    badUuid);
        }
        UUID resolved = lookupOrNull(selfType, legacyId);
        if (resolved == null) {
            throw new BundleIngestException(
                    BundleIngestErrorCode.BUNDLE_CROSS_TENANT_FK_LEAK,
                    selfType + " row carries legacy id " + legacyId
                            + " that this bundle's manifest did not provision; legacy_id_map_"
                            + selfType + " has no resolution");
        }
        row.put(idField, resolved.toString());
    }

    private @Nullable UUID lookupOrNull(EntityType target, UUID legacyGuid) throws SQLException {
        PreparedStatement ps = lookups.get(target);
        if (ps == null) {
            ps = connection.prepareStatement(
                    "SELECT new_uuid FROM " + LegacyIdMapTables.temporaryTableName(target)
                            + " WHERE legacy_guid = ?");
            lookups.put(target, ps);
        }
        ps.setObject(1, legacyGuid);
        try (ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getObject(1, UUID.class) : null;
        }
    }

    private @Nullable UUID lookupCompositeOrNull(
            EntityType target, UUID legacyGuid, UUID clubId) throws SQLException {
        PreparedStatement ps = compositeLookups.get(target);
        if (ps == null) {
            ps = connection.prepareStatement(
                    "SELECT new_uuid FROM " + LegacyIdMapTables.temporaryTableName(target)
                            + " WHERE legacy_guid = ? AND club_id = ?");
            compositeLookups.put(target, ps);
        }
        ps.setObject(1, legacyGuid);
        ps.setObject(2, clubId);
        try (ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getObject(1, UUID.class) : null;
        }
    }

    private boolean isSystemGlobalResolve(EntityType target) {
        EntityPolicy policy = manifest.entityPolicies().get(target);
        return policy != null
                && policy.portPolicy() == EntityPolicy.PortPolicy.SYSTEM_GLOBAL_RESOLVE;
    }

    private static List<ForeignKeyBinding> foreignKeyBindings(Mapper mapper) {
        List<ForeignKeyColumn> declared = mapper.foreignKeyColumns();
        List<ForeignKeyBinding> bindings = new ArrayList<>(declared.size());
        EnumSet<EntityType> declaredTargets = EnumSet.noneOf(EntityType.class);
        for (ForeignKeyColumn fk : declared) {
            bindings.add(new ForeignKeyBinding(fk.column(), fk.target(), fk.disambiguatorColumn()));
            declaredTargets.add(fk.target());
        }
        for (EntityType target : mapper.foreignKeys()) {
            if (!declaredTargets.contains(target)) {
                bindings.add(new ForeignKeyBinding(conventionalForeignKeyField(target), target, null));
            }
        }
        return bindings;
    }

    private static String conventionalForeignKeyField(EntityType target) {
        return target.name().toLowerCase(Locale.ROOT) + "_id";
    }

    private static Map<String, UUID> snapshotDisambiguators(Mapper mapper, ObjectNode row) {
        Map<String, UUID> snapshot = new HashMap<>();
        for (ForeignKeyBinding binding : foreignKeyBindings(mapper)) {
            if (!binding.target().fansOut()) {
                continue;
            }
            String clubField = binding.disambiguatorColumn() != null
                    ? binding.disambiguatorColumn() : REFERENCER_CLUB_FIELD;
            if (snapshot.containsKey(clubField)) {
                continue;
            }
            JsonNode value = row.get(clubField);
            if (value == null || value.isNull()) {
                continue;
            }
            try {
                snapshot.put(clubField, UUID.fromString(value.asText()));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return snapshot;
    }

    private record ForeignKeyBinding(
            String column, EntityType target, @Nullable String disambiguatorColumn) {}

    @Override
    public void close() {
        closeAll(lookups);
        closeAll(compositeLookups);
    }

    private static void closeAll(Map<EntityType, PreparedStatement> statements) {
        for (PreparedStatement ps : statements.values()) {
            try {
                ps.close();
            } catch (SQLException ignored) {
            }
        }
        statements.clear();
    }
}
