package ch.alpenflight.migration.bundle.flight;

import ch.alpenflight.migration.bundle.Coercions;
import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.Mapper;
import ch.alpenflight.migration.bundle.ParitySentinel;
import ch.alpenflight.migration.bundle.SeedReferenceUuids;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class StartTypeMapper implements Mapper {

    static final String LEGACY_GUID = "legacy_guid";

    @ParitySentinel
    static final String CODE = "code";

    private static final String[] COLUMNS = { LEGACY_GUID, CODE };

    private static final Map<Integer, String> LEGACY_ID_TO_V2_CODE = Map.of(
            1, "AEROTOW",
            2, "WINCH_LAUNCH",
            3, "SELF_START",
            4, "EXTERNAL_START",
            5, "MOTOR");

    public static final List<Integer> LEGACY_AIRCRAFT_START_TYPE_ENUM_IDS = List.of(1, 2, 3, 4, 5);

    public static Map<UUID, UUID> legacyEnumIdToSeedPk() {
        Map<UUID, UUID> closure = new LinkedHashMap<>();
        for (int legacyId : LEGACY_AIRCRAFT_START_TYPE_ENUM_IDS) {
            String code = LEGACY_ID_TO_V2_CODE.get(legacyId);
            UUID seedPk = SeedReferenceUuids.startTypeByCode(code);
            if (seedPk == null) {
                throw new IllegalStateException(
                        "Legacy AircraftStartType " + legacyId + " maps to V2 code "
                                + code + " but no t_start_type seed PK exists for it — "
                                + "the V2 seed and StartTypeMapper are out of lockstep.");
            }
            closure.put(
                    UUID.fromString(Coercions.legacyIntIdToUuidString(legacyId)),
                    seedPk);
        }
        return closure;
    }

    @Override
    public EntityType entityType() {
        return EntityType.START_TYPE;
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
        int legacyId = source.getInt("StartTypeId");
        String code = LEGACY_ID_TO_V2_CODE.get(legacyId);
        if (code == null) {
            throw new SQLException(
                    "Legacy StartTypeId " + legacyId + " has no V2 destination — "
                            + "expected one of " + LEGACY_ID_TO_V2_CODE.keySet()
                            + ". A new legacy start type requires a story-level "
                            + "mapping decision (seed + mapper amendment).");
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
