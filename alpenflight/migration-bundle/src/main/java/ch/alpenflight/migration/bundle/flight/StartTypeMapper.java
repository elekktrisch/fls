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

/**
 * SYSTEM_GLOBAL reference. V2 seeds {@code t_start_type} (5 canonical
 * codes); legacy {@code StartTypes.StartTypeId} (int) resolves via
 * lookup against {@code t_start_type.code}. V2 does not carry
 * {@code legacy_int_id} on the start-type row — the join is by code,
 * not by id (same pattern as {@code LanguageMapper} / {@code ClubStateMapper}).
 *
 * <p>The legacy primary key is {@code int}; this mapper widens it through
 * {@link Coercions#legacyIntIdToUuidString} into the new-stack's fixed
 * {@code legacy_id_map_*} byte format. The legacy {@code IsFor*Flights}
 * boolean trio is intentionally dropped — V2's
 * {@code t_start_type.applicable_categories TEXT[]} is the new shape per
 * ADR 0020 (rule 4: SET-MEMBERSHIP) and is owned by the V2 seed, not by
 * any per-row legacy value.
 */
public final class StartTypeMapper implements Mapper {

    static final String LEGACY_GUID = "legacy_guid";

    @ParitySentinel
    static final String CODE = "code";

    private static final String[] COLUMNS = { LEGACY_GUID, CODE };

    private static final Map<Integer, String> LEGACY_ID_TO_V2_CODE = Map.of(
            1, "WINCH_LAUNCH",
            2, "AEROTOW",
            3, "SELF_START",
            4, "EXTERNAL_START",
            5, "MOTOR");

    /**
     * The full legacy {@code AircraftStartType} enum
     * ({@code FLS.Server.Data/Enums/AircraftStartType.cs}: 1=TowingByAircraft,
     * 2=WinchLaunch, 3=SelfStart, 4=ExternalStart, 5=MotorFlightStart). The
     * SYSTEM_GLOBAL closure must enumerate EVERY enum value — a FLIGHT's
     * {@code StartType} int column may carry any of them regardless of which
     * rows the legacy {@code StartTypes} TABLE happens to seed, so the
     * {@code legacy_id_map_START_TYPE} the bundle emits must be enum-driven, not
     * table-row-driven (J-2 T-39: the real FLSTest has a SelfStart(3) flight but
     * the StartTypes table omitted that row, so a table-row-derived map left
     * {@code UUID(0,3)} unresolved → BUNDLE_CROSS_TENANT_FK_LEAK at ingest).
     */
    public static final List<Integer> LEGACY_ENUM_IDS = List.of(1, 2, 3, 4, 5);

    /**
     * The enum-complete SYSTEM_GLOBAL closure the bundle ships as
     * {@code legacy_id_map/START_TYPE.pgcopy}: every legacy enum value's
     * synthetic {@code UUID(0, legacyId)} → its V2 {@code t_start_type} seed PK.
     * Built independently of the legacy {@code StartTypes} table contents so a
     * FLIGHT referencing any of the 5 enum values resolves at ingest.
     */
    public static Map<UUID, UUID> legacyEnumIdToSeedPk() {
        Map<UUID, UUID> closure = new LinkedHashMap<>();
        for (int legacyId : LEGACY_ENUM_IDS) {
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
