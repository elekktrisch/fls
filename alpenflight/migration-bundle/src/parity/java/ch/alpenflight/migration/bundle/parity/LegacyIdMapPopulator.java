package ch.alpenflight.migration.bundle.parity;

import ch.alpenflight.migration.bundle.EntityType;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class LegacyIdMapPopulator {

    private LegacyIdMapPopulator() { }

    public static Maps populate(
            Connection postgresConnection,
            Map<EntityType, List<JsonNode>> systemGlobalBundleRows) throws SQLException {
        Map<EntityType, Map<UUID, UUID>> resolvedByEntity = new LinkedHashMap<>();
        for (Map.Entry<EntityType, List<JsonNode>> entry
                : systemGlobalBundleRows.entrySet()) {
            EntityType entity = entry.getKey();
            ResolverSpec spec = resolverSpecFor(entity);
            Map<UUID, UUID> resolved = resolveOne(postgresConnection, spec, entry.getValue());
            resolvedByEntity.put(entity, resolved);
        }
        return new Maps(resolvedByEntity);
    }

    private static ResolverSpec resolverSpecFor(EntityType entity) {
        return switch (entity) {
            case COUNTRY -> new ResolverSpec("t_country", "iso2_code", "iso2_code");
            case LANGUAGE -> new ResolverSpec("t_language", "code", "code");
            case CLUB_STATE -> new ResolverSpec("t_club_state", "code", "code");
            default -> throw new IllegalArgumentException(
                    "No SYSTEM_GLOBAL resolver wired for " + entity + " in the S-187 "
                            + "vertical slice. S-187a widens to MEMBER_STATE / "
                            + "PERSON_CATEGORY / FLIGHT-group reference tables.");
        };
    }

    private static Map<UUID, UUID> resolveOne(
            Connection connection,
            ResolverSpec spec,
            List<JsonNode> bundleRows) throws SQLException {
        if (bundleRows.isEmpty()) {
            return Map.of();
        }
        Map<String, UUID> seedIdByNaturalKey = readSeedIdByNaturalKey(connection, spec);
        Map<UUID, UUID> seedIdByLegacyGuid = new LinkedHashMap<>();
        for (JsonNode row : bundleRows) {
            UUID legacyGuid = UUID.fromString(row.get("legacy_guid").asText());
            String naturalKey = normalizeNaturalKey(row.get(spec.bundleLookupField()).asText());
            UUID seedId = seedIdByNaturalKey.get(naturalKey);
            if (seedId == null) {
                throw new IllegalStateException(
                        "Bundle row for " + spec.destinationTable() + " carries legacy_guid "
                                + legacyGuid + " with natural key '" + naturalKey
                                + "', which no row of " + spec.destinationTable()
                                + "." + spec.destinationLookupColumn() + " holds — the V2 "
                                + "seed catalogue must contain every natural key the "
                                + "producer emitted.");
            }
            UUID alreadyMapped = seedIdByLegacyGuid.put(legacyGuid, seedId);
            if (alreadyMapped != null && !alreadyMapped.equals(seedId)) {
                throw new IllegalStateException(
                        "Bundle emitted legacy_guid " + legacyGuid + " twice for "
                                + spec.destinationTable() + " with conflicting natural keys — "
                                + "a legacy primary key must resolve to exactly one seed row.");
            }
        }
        return seedIdByLegacyGuid;
    }

    private static Map<String, UUID> readSeedIdByNaturalKey(
            Connection connection, ResolverSpec spec) throws SQLException {
        String sql = "SELECT " + spec.destinationLookupColumn() + ", id FROM "
                + spec.destinationTable();
        Map<String, UUID> seedIdByNaturalKey = new HashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                seedIdByNaturalKey.put(
                        normalizeNaturalKey(rs.getString(1)),
                        UUID.fromString(rs.getString(2)));
            }
        }
        return seedIdByNaturalKey;
    }

    private static String normalizeNaturalKey(String rawKey) {
        return rawKey.trim().toLowerCase(Locale.ROOT);
    }

    private record ResolverSpec(
            String destinationTable,
            String destinationLookupColumn,
            String bundleLookupField) {
    }

    public record Maps(Map<EntityType, Map<UUID, UUID>> newIdByLegacyGuidByEntity) {

        public Map<UUID, UUID> requireFor(EntityType target) {
            Map<UUID, UUID> map = newIdByLegacyGuidByEntity.get(target);
            if (map == null) {
                throw new IllegalStateException(
                        "No legacy_id_map populated for " + target
                                + " — populate() must be invoked with the bundle's "
                                + "SYSTEM_GLOBAL entries before any FULL_PORT mapper runs.");
            }
            return map;
        }
    }
}
