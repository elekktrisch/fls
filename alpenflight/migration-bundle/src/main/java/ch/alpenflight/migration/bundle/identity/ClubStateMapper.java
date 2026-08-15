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

public final class ClubStateMapper implements Mapper {

    static final String LEGACY_GUID = "legacy_guid";

    @ParitySentinel
    static final String CODE = "code";

    private static final String[] COLUMNS = { LEGACY_GUID, CODE };

    private static final int LEGACY_CLUB_STATE_SYSTEM = 0;
    private static final int LEGACY_CLUB_STATE_ACTIVE = 1;
    private static final int LEGACY_CLUB_STATE_PASSIVE = 2;
    private static final int LEGACY_CLUB_STATE_INACTIVE = 3;

    private static final Map<Integer, String> LEGACY_ID_TO_V2_CODE = Map.of(
            LEGACY_CLUB_STATE_SYSTEM, "ACTIVE",
            LEGACY_CLUB_STATE_ACTIVE, "ACTIVE",
            LEGACY_CLUB_STATE_PASSIVE, "CLOSED",
            LEGACY_CLUB_STATE_INACTIVE, "SUSPENDED");

    public static String v2CodeForLegacyId(int legacyClubStateId) {
        return LEGACY_ID_TO_V2_CODE.get(legacyClubStateId);
    }

    @Override
    public EntityType entityType() {
        return EntityType.CLUB_STATE;
    }

    @Override
    public String[] wireColumns() {
        return COLUMNS.clone();
    }

    @Override
    public List<EntityType> foreignKeyTargets() {
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
