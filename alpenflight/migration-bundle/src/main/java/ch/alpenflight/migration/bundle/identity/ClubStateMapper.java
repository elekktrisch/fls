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
 * (ACTIVE / SUSPENDED / CLOSED). Legacy {@code ClubStateId = 0} ("System")
 * has no destination — producer-side {@code SELECT} filters it out;
 * downstream {@code Clubs} pointing at id=0 follow the system-row drop
 * policy.
 *
 * <p>The legacy → V2 translation is value-bound, not a simple case-fold:
 * {@code Passiv} maps to {@code CLOSED} and {@code Inactive} maps to
 * {@code SUSPENDED}. Resolution stays on the legacy ID rather than the
 * name string so a future legacy rename to "Aktiv" / "Geschlossen" does
 * not break the bundle.
 */
public final class ClubStateMapper implements Mapper {

    static final String LEGACY_GUID = "legacy_guid";

    @ParitySentinel
    static final String CODE = "code";

    private static final String[] COLUMNS = { LEGACY_GUID, CODE };

    private static final Map<Integer, String> LEGACY_ID_TO_V2_CODE = Map.of(
            1, "ACTIVE",
            2, "CLOSED",
            3, "SUSPENDED");

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
                    "Legacy ClubStateId " + legacyId + " has no V2 destination — "
                            + "the producer query must filter ClubStateId NOT IN (0) "
                            + "and any value outside (1, 2, 3) requires a story-level "
                            + "mapping decision.");
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
