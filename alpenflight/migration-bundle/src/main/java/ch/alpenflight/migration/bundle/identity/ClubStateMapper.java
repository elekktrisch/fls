package ch.alpenflight.migration.bundle.identity;

import ch.alpenflight.migration.bundle.Coercions;
import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.Mapper;
import ch.alpenflight.migration.bundle.ParitySentinel;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SYSTEM_GLOBAL reference. Maps the legacy {@code ClubStates} catalogue
 * (4 rows: System / Active / Passiv / Inactive) to the V2 3-state lifecycle
 * (ACTIVE / SUSPENDED / CLOSED). The legacy enum
 * ({@code FLS.Data.WebApi/Club/ClubState.cs}) is {@code System=0, Active=1,
 * Passive=2, Inactive=3}; ALL four values resolve to a V2 code (J-0c T-16) so
 * a real legacy club referencing any of them migrates without a fail-closed
 * gap.
 *
 * <p>The legacy → V2 translation is value-bound, not a simple case-fold:
 * <ul>
 *   <li>{@code Active(1) → ACTIVE} — the operating tenant.</li>
 *   <li>{@code Passive(2) → CLOSED} — legacy "Passiv club" is a club WITHOUT
 *       tenant activities, no users, information-only (legacy seed comment);
 *       it is permanently non-operational, i.e. CLOSED, not merely SUSPENDED.</li>
 *   <li>{@code Inactive(3) → SUSPENDED} — legacy "Inactive club" was active
 *       before (legacy seed comment); a reactivatable dormant tenant ⇒
 *       SUSPENDED, not CLOSED.</li>
 *   <li>{@code System(0) → ACTIVE} — the FLS internal "System-Verein"
 *       (ClubKey {@code SystemClub}) carrying the default system/workflow
 *       users (FLSTest seed: the single {@code ClubStateId=0} club). The
 *       legacy comment "will not be shown in club entities" is a UI
 *       presentation rule, not a lifecycle-dead signal. The V2 lifecycle has
 *       no SYSTEM state, and a migrated system club must stay a usable tenant
 *       (its owned users depend on it); CLOSED/SUSPENDED would break it. So
 *       ACTIVE is the only defensible target. (J-0c T-16 parity decision.)</li>
 * </ul>
 * Resolution stays on the legacy ID rather than the name string so a future
 * legacy rename to "Aktiv" / "Geschlossen" does not break the bundle.
 */
public final class ClubStateMapper implements Mapper {

    static final String LEGACY_GUID = "legacy_guid";

    @ParitySentinel
    static final String CODE = "code";

    private static final String[] COLUMNS = { LEGACY_GUID, CODE };

    private static final Map<Integer, String> LEGACY_ID_TO_V2_CODE = Map.of(
            0, "ACTIVE",
            1, "ACTIVE",
            2, "CLOSED",
            3, "SUSPENDED");

    /**
     * The legacy {@code ClubStateId} INT to V2 lifecycle-code translation, shared
     * with the migration producer so {@code ManifestBuilder} can resolve a Club's
     * {@code clubStateId} to the seed PK by the same value-binding this mapper
     * uses (avoids a second, drift-prone copy of the mapping). Covers every legacy
     * enum value (0/1/2/3 — see class Javadoc for the per-value parity decision);
     * returns {@code null} only for a value OUTSIDE the known enum, which is a
     * data-corruption signal the caller fails closed on.
     */
    public static String v2CodeForLegacyId(int legacyClubStateId) {
        return LEGACY_ID_TO_V2_CODE.get(legacyClubStateId);
    }

    @Override
    public EntityType entityType() {
        return EntityType.CLUB_STATE;
    }

    @Override
    public String[] columns() {
        return COLUMNS.clone();
    }

    @Override
    public List<EntityType> foreignKeys() {
        return List.of();
    }

    @Override
    public void writeNdjson(ResultSet source, JsonGenerator target)
            throws SQLException, IOException {
        int legacyId = source.getInt("ClubStateId");
        String code = LEGACY_ID_TO_V2_CODE.get(legacyId);
        if (code == null) {
            throw new SQLException(
                    "Legacy ClubStateId " + legacyId + " is outside the known legacy "
                            + "ClubState enum (System=0, Active=1, Passive=2, Inactive=3); "
                            + "a new value requires a story-level mapping decision before "
                            + "it can migrate.");
        }
        target.writeStartObject();
        target.writeStringField(LEGACY_GUID,
                Coercions.legacyIntIdToUuidString(legacyId));
        target.writeStringField(CODE, code);
        target.writeEndObject();
    }

    @Override
    public void readEntity(JsonNode source, PreparedStatement target) throws SQLException {
        target.setObject(1, UUID.fromString(source.get(LEGACY_GUID).asText()));
        target.setString(2, source.get(CODE).asText());
    }
}
