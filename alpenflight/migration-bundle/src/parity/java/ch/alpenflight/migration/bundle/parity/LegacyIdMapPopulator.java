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
import java.util.Map;
import java.util.UUID;

/**
 * Stand-in for S-141's SYSTEM_GLOBAL-resolve stage. Reads (legacy_guid,
 * lookup_key) pairs from the bundle's SYSTEM_GLOBAL entries and joins them
 * against the V2-seeded destination by lookup column to produce the
 * {@code legacy_guid → new_uuid} translation tables the FK rewriter uses
 * when binding FULL_PORT mapper rows.
 *
 * <p>Vertical-slice scope (S-187): the three SYSTEM_GLOBAL entities the
 * three FULL_PORT mappers in this slice depend on — COUNTRY (joined on
 * {@code iso2_code}), LANGUAGE ({@code code}), CLUB_STATE ({@code code}).
 * S-187a widens to the remaining reference tables alongside the full
 * 28-mapper sweep and persists the resolution into ephemeral
 * {@code legacy_id_map_<entity>} temporary tables the way S-141 will.
 */
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
        // Index bundle rows by lookup key for one-pass resolution; SYSTEM_GLOBAL
        // bundle rows for the three entities this slice covers stay small
        // (~10-250 rows) so a HashMap is fine even on the nightly 10× scale.
        Map<String, UUID> legacyGuidByLookupKey = new HashMap<>();
        for (JsonNode row : bundleRows) {
            String lookupKey = row.get(spec.bundleLookupField()).asText();
            String legacyGuidText = row.get("legacy_guid").asText();
            legacyGuidByLookupKey.put(
                    lookupKey.toLowerCase(java.util.Locale.ROOT),
                    UUID.fromString(legacyGuidText));
        }
        // Single query per entity — pull every (lookup_key, id) and join in
        // memory rather than issuing one query per bundle row.
        String sql = "SELECT " + spec.destinationLookupColumn() + ", id FROM "
                + spec.destinationTable();
        Map<UUID, UUID> resolved = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String destinationKey = rs.getString(1).toLowerCase(java.util.Locale.ROOT);
                UUID destinationId = UUID.fromString(rs.getString(2));
                UUID legacyGuid = legacyGuidByLookupKey.get(destinationKey);
                if (legacyGuid != null) {
                    resolved.put(legacyGuid, destinationId);
                }
            }
        }
        return resolved;
    }

    private record ResolverSpec(
            String destinationTable,
            String destinationLookupColumn,
            String bundleLookupField) {
    }

    /**
     * Per-entity legacy → new resolution. Keyed by the FK target entity;
     * value is the {@code legacy_guid → t_entity.id} map the FK rewriter
     * looks up when binding a FULL_PORT mapper row.
     */
    public record Maps(Map<EntityType, Map<UUID, UUID>> byEntity) {

        public Map<UUID, UUID> requireFor(EntityType target) {
            Map<UUID, UUID> map = byEntity.get(target);
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
