package ch.alpenflight.migration.tool;

import ch.alpenflight.migration.bundle.EntityPolicy;
import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.MapperLegacyBindings;
import ch.alpenflight.migration.bundle.SeedReferenceUuids;
import ch.alpenflight.migration.bundle.identity.ClubStateMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
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
     * to {@code false} — the operator / server can refine post-ingest.
     *
     * <p><strong>System-global FK resolution (J-0c T-15).</strong> The new stack
     * seeds COUNTRY + CLUB_STATE with deterministic seed PKs that do NOT match
     * legacy's Country GUIDs or the synthetic INT-keyed club-state. The manifest
     * feeds {@code DeploymentProvisioningService}'s {@code t_club} INSERT
     * directly (before any NDJSON FK resolution runs), so the declaration must
     * carry the RESOLVED seed PKs or the insert FK-violates
     * {@code fk_club_country_id} / {@code fk_club_club_state_id}:
     * <ul>
     *   <li>{@code countryId}: legacy {@code Clubs.CountryId} GUID →
     *       {@code Countries.CountryCodeIso2} → seed {@code t_country.id} via
     *       {@link SeedReferenceUuids#countryByIso2}.</li>
     *   <li>{@code clubStateId}: legacy {@code Clubs.ClubStateId} INT → V2
     *       lifecycle code ({@link ClubStateMapper#v2CodeForLegacyId}) → seed
     *       {@code t_club_state.id} via {@link SeedReferenceUuids#clubStateByCode}.</li>
     * </ul>
     * The bundle separately emits {@code legacy_id_map/COUNTRY.pgcopy} +
     * {@code CLUB_STATE.pgcopy} ({@link BundleWriter}) so the CLUB NDJSON's FK
     * resolution path lands the same seed PKs.
     */
    private static List<ManifestModel.ClubDeclaration> readClubDeclarations(
            LegacyJdbcReader reader) {
        Map<UUID, String> iso2ByCountryGuid = readCountryIso2ByGuid(reader);
        String sql = "SELECT ClubId, Clubname, ClubKey, CountryId, ClubStateId FROM Clubs";
        List<ManifestModel.ClubDeclaration> clubs = new ArrayList<>();
        try (ResultSet rs = reader.openEntityCursor(sql)) {
            while (rs.next()) {
                UUID legacyClubId = UUID.fromString(rs.getString("ClubId"));
                String name = rs.getString("Clubname");
                String clubKey = rs.getString("ClubKey");
                UUID legacyCountryGuid = UUID.fromString(rs.getString("CountryId"));
                int legacyClubStateId = rs.getInt("ClubStateId");
                UUID countryId = resolveCountrySeedPk(legacyCountryGuid, iso2ByCountryGuid);
                UUID clubStateId = resolveClubStateSeedPk(legacyClubStateId);
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

    /**
     * Reads legacy {@code Countries} into a {@code CountryId GUID → CountryCodeIso2}
     * map. Small bounded table (~196 rows); read once for the whole manifest.
     */
    static Map<UUID, String> readCountryIso2ByGuid(LegacyJdbcReader reader) {
        Map<UUID, String> iso2ByGuid = new HashMap<>();
        try (ResultSet rs = reader.openEntityCursor(
                "SELECT CountryId, CountryCodeIso2 FROM Countries")) {
            while (rs.next()) {
                String guidText = rs.getString("CountryId");
                String iso2 = rs.getString("CountryCodeIso2");
                if (guidText != null) {
                    iso2ByGuid.put(UUID.fromString(guidText), iso2);
                }
            }
        } catch (SQLException e) {
            throw new ExportException(ExitCode.IO_ERROR,
                    "Failed to read legacy Countries for seed-PK resolution: "
                            + e.getMessage(), e);
        }
        return iso2ByGuid;
    }

    /** Legacy Country GUID → ISO2 → seed {@code t_country.id}; fail-closed on a gap. */
    static UUID resolveCountrySeedPk(UUID legacyCountryGuid, Map<UUID, String> iso2ByCountryGuid) {
        String iso2 = iso2ByCountryGuid.get(legacyCountryGuid);
        if (iso2 == null) {
            throw new ExportException(ExitCode.IO_ERROR,
                    "Club references legacy Country " + legacyCountryGuid
                            + " absent from the legacy Countries table — cannot resolve "
                            + "its ISO2 to a new-stack seed country.");
        }
        UUID seedPk = SeedReferenceUuids.countryByIso2(iso2);
        if (seedPk == null) {
            throw new ExportException(ExitCode.IO_ERROR,
                    "Legacy Country " + legacyCountryGuid + " has ISO2 '" + iso2
                            + "' which is not in the new-stack t_country seed catalogue.");
        }
        return seedPk;
    }

    /** Legacy ClubState INT → V2 code → seed {@code t_club_state.id}; fail-closed on a gap. */
    static UUID resolveClubStateSeedPk(int legacyClubStateId) {
        String code = ClubStateMapper.v2CodeForLegacyId(legacyClubStateId);
        if (code == null) {
            throw new ExportException(ExitCode.IO_ERROR,
                    "Club references legacy ClubStateId " + legacyClubStateId
                            + " which is outside the known legacy ClubState enum "
                            + "(System=0, Active=1, Passive=2, Inactive=3) — a new value "
                            + "needs a story-level mapping decision before it can migrate.");
        }
        UUID seedPk = SeedReferenceUuids.clubStateByCode(code);
        if (seedPk == null) {
            throw new ExportException(ExitCode.IO_ERROR,
                    "V2 club-state code '" + code + "' is not in the t_club_state seed.");
        }
        return seedPk;
    }

    private static String deriveSlug(String clubKey, UUID legacyClubId) {
        String base = clubKey == null ? "" : clubKey.trim().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return base.isBlank() ? "club-" + legacyClubId : base;
    }
}
