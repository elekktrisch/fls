package ch.alpenflight.migration.bundle.flight;

import ch.alpenflight.migration.bundle.Coercions;
import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.Mapper;
import ch.alpenflight.migration.bundle.ParityIgnore;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * Cross-tenant aggregate root per ADR 0008's 2026-05-24 amendment: legacy
 * {@code Aircrafts.AircraftId} → {@code t_aircraft.id}.
 *
 * <p>{@code managing_club_id} (V10, NOT NULL) is computed producer-side
 * via cascade: legacy {@code OwnerClubId} → single-{@code PersonClub} of
 * {@code AircraftOwnerPersonId} in bundle → drop+warn
 * {@code AIRCRAFT_NO_MANAGING_CLUB}. The producer aliases the resolved
 * value as the cursor's {@code ManagingClubId} column; this mapper reads
 * it verbatim. {@code owner_club_id} (V3, nullable) is plain ownership
 * metadata orthogonal to the tenant identity.
 *
 * <p>Outgoing FKs per {@link Mapper#foreignKeys()}: {@link EntityType#CLUB}
 * (owner + manager), {@link EntityType#PERSON}
 * ({@code aircraft_owner_person_id} — cross-tenant tenant-bypass per the
 * {@link ch.alpenflight.migration.bundle.Manifest}
 * {@code TENANT_BYPASS_ALLOW_LIST}), {@link EntityType#LOCATION}
 * ({@code homebase_id} — cross-tenant ride-through into the tenant-scoped
 * Location replica matching the Aircraft's managing club).
 * {@code aircraft_type_id} + the two counter-unit-type FKs resolve through
 * V3's seeded {@code legacy_int_id} map — those entities live outside
 * {@link EntityType} and are not per-bundle dependencies.
 *
 * <p>Mapper-side {@code spot_link} {@code ^https://} reject at
 * {@link #readEntity}: defense-in-depth ahead of the V3
 * {@code ck_aircraft_spot_link_https} schema CHECK (ADR 0022 directive 2
 * A10 SSRF carve-out). Fails the bundle row with
 * {@code BUNDLE_AIRCRAFT_SPOT_LINK_NOT_HTTPS} rather than letting the
 * row land and surface as an opaque mid-COPY constraint violation.
 *
 * <p>Aircraft ownership-exclusivity invariant (V3 SQL COMMENT):
 * {@code owner_club_id} XOR {@code aircraft_owner_person_id} —
 * service-layer enforced. Mapper passes through; producer (S-139) is
 * responsible for stripping malformed legacy rows before they hit the
 * bundle.
 *
 * <p>Cross-bundle Aircraft dedupe is out of scope: per S-183,
 * {@code legacy_id_map_aircraft} is bundle-local ({@code ON COMMIT DROP}).
 * Future multi-bundle merge of the same physical aircraft requires manual
 * resolution via S-051.
 *
 * <p>Legacy ASP.NET artifacts dropped: {@code OwnerId},
 * {@code OwnershipType}, {@code RecordState}, {@code IsDeleted}.
 */
public final class AircraftMapper implements Mapper {

    /**
     * Thrown by {@link #readEntity} when the producer-side hygiene failed
     * to strip a non-{@code https://} {@code spot_link}. Surfaces as a
     * row-attributable error before the V3 CHECK fires mid-COPY.
     */
    public static final String NOT_HTTPS_SPOT_LINK_ERROR =
            "BUNDLE_AIRCRAFT_SPOT_LINK_NOT_HTTPS";

    static final String LEGACY_GUID = "legacy_guid";
    static final String MANAGING_CLUB_ID = "managing_club_id";
    static final String OWNER_CLUB_ID = "owner_club_id";
    static final String AIRCRAFT_TYPE_ID = "aircraft_type_id";
    static final String MANUFACTURER_NAME = "manufacturer_name";
    static final String AIRCRAFT_MODEL = "aircraft_model";
    static final String IMMATRICULATION = "immatriculation";
    static final String COMPETITION_SIGN = "competition_sign";
    static final String FLARM_ID = "flarm_id";
    static final String AIRCRAFT_SERIAL_NUMBER = "aircraft_serial_number";
    static final String YEAR_OF_MANUFACTURE = "year_of_manufacture";
    static final String NOISE_CLASS = "noise_class";
    static final String NOISE_LEVEL = "noise_level";
    static final String MTOM = "mtom";
    static final String NR_OF_SEATS = "nr_of_seats";
    static final String AIRCRAFT_OWNER_PERSON_ID = "aircraft_owner_person_id";
    static final String FLIGHT_OPERATING_COUNTER_UNIT_TYPE_ID =
            "flight_operating_counter_unit_type_id";
    static final String ENGINE_OPERATING_COUNTER_UNIT_TYPE_ID =
            "engine_operating_counter_unit_type_id";
    static final String HOMEBASE_ID = "homebase_id";
    static final String SPOT_LINK = "spot_link";
    static final String IS_TOWING_OR_WINCH_REQUIRED = "is_towing_or_winch_required";
    static final String IS_TOWING_START_ALLOWED = "is_towing_start_allowed";
    static final String IS_WINCH_START_ALLOWED = "is_winch_start_allowed";
    static final String IS_TOWING_AIRCRAFT = "is_towing_aircraft";
    static final String IS_FAST_ENTRY_RECORD = "is_fast_entry_record";
    static final String COMMENT = "comment";
    static final String DAEC_INDEX = "daec_index";

    @ParityIgnore
    static final String CREATED_ON = "created_on";
    static final String CREATED_BY_USER_ID = "created_by_user_id";
    @ParityIgnore
    static final String MODIFIED_ON = "modified_on";
    static final String MODIFIED_BY_USER_ID = "modified_by_user_id";
    static final String DELETED_ON = "deleted_on";
    static final String DELETED_BY_USER_ID = "deleted_by_user_id";

    private static final String[] COLUMNS = {
            LEGACY_GUID, MANAGING_CLUB_ID, OWNER_CLUB_ID,
            AIRCRAFT_TYPE_ID,
            MANUFACTURER_NAME, AIRCRAFT_MODEL, IMMATRICULATION, COMPETITION_SIGN,
            FLARM_ID, AIRCRAFT_SERIAL_NUMBER, YEAR_OF_MANUFACTURE,
            NOISE_CLASS, NOISE_LEVEL, MTOM, NR_OF_SEATS,
            AIRCRAFT_OWNER_PERSON_ID,
            FLIGHT_OPERATING_COUNTER_UNIT_TYPE_ID, ENGINE_OPERATING_COUNTER_UNIT_TYPE_ID,
            HOMEBASE_ID, SPOT_LINK,
            IS_TOWING_OR_WINCH_REQUIRED, IS_TOWING_START_ALLOWED,
            IS_WINCH_START_ALLOWED, IS_TOWING_AIRCRAFT, IS_FAST_ENTRY_RECORD,
            COMMENT, DAEC_INDEX,
            CREATED_ON, CREATED_BY_USER_ID,
            MODIFIED_ON, MODIFIED_BY_USER_ID,
            DELETED_ON, DELETED_BY_USER_ID
    };

    @Override
    public EntityType entityType() {
        return EntityType.AIRCRAFT;
    }

    @Override
    public String[] columns() {
        return COLUMNS.clone();
    }

    @Override
    public List<EntityType> foreignKeys() {
        return List.of(EntityType.CLUB, EntityType.PERSON, EntityType.LOCATION);
    }

    @Override
    public void writeNdjson(ResultSet source, JsonGenerator target)
            throws SQLException, IOException {
        target.writeStartObject();
        target.writeStringField(LEGACY_GUID, source.getString("AircraftId"));
        target.writeStringField(MANAGING_CLUB_ID, source.getString("ManagingClubId"));
        Coercions.writeOptionalString(target, OWNER_CLUB_ID,
                source.getString("AircraftOwnerClubId"));
        target.writeStringField(AIRCRAFT_TYPE_ID,
                Coercions.legacyIntIdToUuidString(source.getInt("AircraftType")));
        Coercions.writeOptionalString(target, MANUFACTURER_NAME,
                source.getString("ManufacturerName"));
        Coercions.writeOptionalString(target, AIRCRAFT_MODEL,
                source.getString("AircraftModel"));
        target.writeStringField(IMMATRICULATION, source.getString("Immatriculation"));
        Coercions.writeOptionalString(target, COMPETITION_SIGN,
                source.getString("CompetitionSign"));
        Coercions.writeOptionalString(target, FLARM_ID, source.getString("FLARMId"));
        Coercions.writeOptionalString(target, AIRCRAFT_SERIAL_NUMBER,
                source.getString("AircraftSerialNumber"));
        // Legacy datetime2 → DATE; JDBC truncates the time portion via getDate().
        Coercions.writeOptionalDate(target, YEAR_OF_MANUFACTURE,
                source.getDate("YearOfManufacture"));
        Coercions.writeOptionalString(target, NOISE_CLASS, source.getString("NoiseClass"));
        Coercions.writeOptionalBigDecimal(target, NOISE_LEVEL,
                source.getBigDecimal("NoiseLevel"));
        Coercions.writeOptionalInt(target, MTOM, source.getObject("MTOM", Integer.class));
        Coercions.writeOptionalInt(target, NR_OF_SEATS,
                source.getObject("NrOfSeats", Integer.class));
        Coercions.writeOptionalString(target, AIRCRAFT_OWNER_PERSON_ID,
                source.getString("AircraftOwnerPersonId"));
        Coercions.writeOptionalString(target, FLIGHT_OPERATING_COUNTER_UNIT_TYPE_ID,
                optionalLegacyIntIdAsUuid(source, "FlightOperatingCounterUnitTypeId"));
        Coercions.writeOptionalString(target, ENGINE_OPERATING_COUNTER_UNIT_TYPE_ID,
                optionalLegacyIntIdAsUuid(source, "EngineOperatingCounterUnitTypeId"));
        Coercions.writeOptionalString(target, HOMEBASE_ID, source.getString("HomebaseId"));
        Coercions.writeOptionalString(target, SPOT_LINK, source.getString("SpotLink"));
        target.writeBooleanField(IS_TOWING_OR_WINCH_REQUIRED,
                source.getBoolean("IsTowingOrWinchRequired"));
        target.writeBooleanField(IS_TOWING_START_ALLOWED,
                source.getBoolean("IsTowingstartAllowed"));
        target.writeBooleanField(IS_WINCH_START_ALLOWED,
                source.getBoolean("IsWinchstartAllowed"));
        target.writeBooleanField(IS_TOWING_AIRCRAFT, source.getBoolean("IsTowingAircraft"));
        target.writeBooleanField(IS_FAST_ENTRY_RECORD, source.getBoolean("IsFastEntryRecord"));
        Coercions.writeOptionalString(target, COMMENT, source.getString("Comment"));
        Coercions.writeOptionalInt(target, DAEC_INDEX,
                source.getObject("DaecIndex", Integer.class));
        Coercions.writeRequiredTimestamp(target, CREATED_ON, source.getTimestamp("CreatedOn"));
        target.writeStringField(CREATED_BY_USER_ID, source.getString("CreatedByUserId"));
        Coercions.writeOptionalTimestamp(target, MODIFIED_ON, source.getTimestamp("ModifiedOn"));
        Coercions.writeOptionalString(target, MODIFIED_BY_USER_ID,
                source.getString("ModifiedByUserId"));
        Coercions.writeOptionalTimestamp(target, DELETED_ON, source.getTimestamp("DeletedOn"));
        Coercions.writeOptionalString(target, DELETED_BY_USER_ID,
                source.getString("DeletedByUserId"));
        target.writeEndObject();
    }

    private static @org.jspecify.annotations.Nullable String optionalLegacyIntIdAsUuid(
            ResultSet source, String legacyColumn) throws SQLException {
        Integer value = source.getObject(legacyColumn, Integer.class);
        return value == null ? null : Coercions.legacyIntIdToUuidString(value);
    }

    @Override
    public void readEntity(JsonNode source, PreparedStatement target) throws SQLException {
        String spotLink = Coercions.readStringOrNull(source, SPOT_LINK);
        if (spotLink != null && !spotLink.regionMatches(true, 0, "https://", 0, 8)) {
            throw new SQLException(NOT_HTTPS_SPOT_LINK_ERROR
                    + ": Aircraft.spot_link must start with 'https://' — "
                    + "producer-side hygiene fell through, would otherwise trip "
                    + "ck_aircraft_spot_link_https mid-COPY. Got: " + spotLink);
        }
        int position = 1;
        target.setObject(position++, UUID.fromString(source.get(LEGACY_GUID).asText()));
        target.setObject(position++, UUID.fromString(source.get(MANAGING_CLUB_ID).asText()));
        target.setObject(position++, Coercions.readUuidOrNull(source, OWNER_CLUB_ID));
        target.setObject(position++, UUID.fromString(source.get(AIRCRAFT_TYPE_ID).asText()));
        target.setString(position++, Coercions.readStringOrNull(source, MANUFACTURER_NAME));
        target.setString(position++, Coercions.readStringOrNull(source, AIRCRAFT_MODEL));
        target.setString(position++, source.get(IMMATRICULATION).asText());
        target.setString(position++, Coercions.readStringOrNull(source, COMPETITION_SIGN));
        target.setString(position++, Coercions.readStringOrNull(source, FLARM_ID));
        target.setString(position++, Coercions.readStringOrNull(source, AIRCRAFT_SERIAL_NUMBER));
        target.setDate(position++, Coercions.readDateOrNull(source, YEAR_OF_MANUFACTURE));
        target.setString(position++, Coercions.readStringOrNull(source, NOISE_CLASS));
        target.setBigDecimal(position++, Coercions.readBigDecimalOrNull(source, NOISE_LEVEL));
        target.setObject(position++, Coercions.readIntOrNull(source, MTOM));
        target.setObject(position++, Coercions.readIntOrNull(source, NR_OF_SEATS));
        target.setObject(position++,
                Coercions.readUuidOrNull(source, AIRCRAFT_OWNER_PERSON_ID));
        target.setObject(position++,
                Coercions.readUuidOrNull(source, FLIGHT_OPERATING_COUNTER_UNIT_TYPE_ID));
        target.setObject(position++,
                Coercions.readUuidOrNull(source, ENGINE_OPERATING_COUNTER_UNIT_TYPE_ID));
        target.setObject(position++, Coercions.readUuidOrNull(source, HOMEBASE_ID));
        target.setString(position++, spotLink);
        target.setObject(position++, source.get(IS_TOWING_OR_WINCH_REQUIRED).asBoolean());
        target.setObject(position++, source.get(IS_TOWING_START_ALLOWED).asBoolean());
        target.setObject(position++, source.get(IS_WINCH_START_ALLOWED).asBoolean());
        target.setObject(position++, source.get(IS_TOWING_AIRCRAFT).asBoolean());
        target.setObject(position++, source.get(IS_FAST_ENTRY_RECORD).asBoolean());
        target.setString(position++, Coercions.readStringOrNull(source, COMMENT));
        target.setObject(position++, Coercions.readIntOrNull(source, DAEC_INDEX));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, CREATED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, CREATED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, MODIFIED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, MODIFIED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, DELETED_ON));
        target.setObject(position, Coercions.readUuidOrNull(source, DELETED_BY_USER_ID));
    }
}
