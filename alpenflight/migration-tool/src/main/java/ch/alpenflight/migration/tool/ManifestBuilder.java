package ch.alpenflight.migration.tool;

import ch.alpenflight.migration.bundle.Coercions;
import ch.alpenflight.migration.bundle.EntityPolicy;
import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.MapperLegacyBindings;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Assembles {@link ManifestModel} from the live legacy database + the SELECT
 * registry. Two responsibilities:
 *
 * <ul>
 *   <li>Translate each registered {@link EntityType}'s
 *       {@link MapperLegacyBindings.PortPolicy} into the wire
 *       {@link EntityPolicy} the server re-validates; every other
 *       {@code EntityType} gets an {@code unmappedReason} so the server's
 *       coverage gate passes (never silently omitted).</li>
 *   <li>Read one {@link ManifestModel.ClubDeclaration} per legacy Club so the
 *       server can provision Deployments + Clubs before ingest.</li>
 * </ul>
 */
public final class ManifestBuilder {

    /** Server's {@code Manifest.CURRENT_SCHEMA_VERSION} — pinned for the slice. */
    static final int SCHEMA_VERSION = 1;

    private static final String UNMAPPED_REASON =
            "ENTITY_NOT_YET_BOUND: no MapperLegacyBindings SELECT registered "
                    + "(grows as S-187a extends the registry)";

    private ManifestBuilder() {
    }

    public static ManifestModel build(LegacyJdbcReader reader,
                                       List<EntityType> registeredEntities,
                                       String deploymentName) {
        Map<EntityType, EntityPolicy> policies = new EnumMap<>(EntityType.class);
        for (EntityType entity : registeredEntities) {
            policies.put(entity, toEntityPolicy(MapperLegacyBindings.portPolicy(entity)));
        }
        Map<EntityType, String> unmapped = new EnumMap<>(EntityType.class);
        for (EntityType entity : EntityType.values()) {
            if (!policies.containsKey(entity)) {
                unmapped.put(entity, UNMAPPED_REASON);
            }
        }
        List<ManifestModel.ClubDeclaration> clubs = readClubDeclarations(reader);
        // Legacy has no "primary club" concept; this is an arbitrary first-row
        // pick the operator refines post-ingest (mirrors the deriveSlug note).
        UUID primaryClubId = clubs.isEmpty() ? null : clubs.get(0).legacyClubId();
        return new ManifestModel(
                SCHEMA_VERSION, deploymentName, clubs, primaryClubId, policies, unmapped);
    }

    private static EntityPolicy toEntityPolicy(MapperLegacyBindings.PortPolicy policy) {
        return switch (policy) {
            case FULL_PORT -> new EntityPolicy(
                    EntityPolicy.PortPolicy.FULL_PORT,
                    EntityPolicy.TombstonePolicy.PORT_ALL,
                    Set.of(),
                    List.of());
            case SYSTEM_GLOBAL -> new EntityPolicy(
                    EntityPolicy.PortPolicy.SYSTEM_GLOBAL_RESOLVE,
                    EntityPolicy.TombstonePolicy.SKIP_DELETED,
                    Set.of(),
                    List.of());
        };
    }

    /**
     * One declaration per legacy Club. Legacy {@code Clubs} has no slug /
     * public-registration columns (those are new-stack concepts), so the
     * slug is derived from {@code ClubKey} and public registration defaults
     * to {@code false} — the operator / server can refine post-ingest. The
     * {@code clubStateId} mirrors the bundle wire form: legacy INT →
     * synthetic UUID via {@link Coercions#legacyIntIdToUuidString}, matching
     * {@code ClubMapper.writeNdjson}.
     */
    private static List<ManifestModel.ClubDeclaration> readClubDeclarations(
            LegacyJdbcReader reader) {
        String sql = "SELECT ClubId, Clubname, ClubKey, CountryId, ClubStateId FROM Clubs";
        List<ManifestModel.ClubDeclaration> clubs = new ArrayList<>();
        try (ResultSet rs = reader.openEntityCursor(sql)) {
            while (rs.next()) {
                UUID legacyClubId = UUID.fromString(rs.getString("ClubId"));
                String name = rs.getString("Clubname");
                String clubKey = rs.getString("ClubKey");
                UUID countryId = UUID.fromString(rs.getString("CountryId"));
                UUID clubStateId = UUID.fromString(
                        Coercions.legacyIntIdToUuidString(rs.getInt("ClubStateId")));
                clubs.add(new ManifestModel.ClubDeclaration(
                        legacyClubId, name, deriveSlug(clubKey, legacyClubId),
                        clubKey, false, countryId, clubStateId));
            }
        } catch (SQLException e) {
            throw new ExportException(ExitCode.IO_ERROR,
                    "Failed to read Club declarations: " + e.getMessage(), e);
        }
        return clubs;
    }

    private static String deriveSlug(String clubKey, UUID legacyClubId) {
        String base = clubKey == null ? "" : clubKey.trim().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return base.isBlank() ? "club-" + legacyClubId : base;
    }
}
