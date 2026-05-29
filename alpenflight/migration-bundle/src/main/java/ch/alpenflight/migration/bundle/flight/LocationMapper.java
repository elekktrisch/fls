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
 * Tenant-scoped masterdata per V7: legacy {@code Locations.LocationId} →
 * {@code t_location.id} with a {@code club_id} discriminator. Legacy has
 * no per-club Location ownership, so producer-side fan-out (S-139) emits
 * one bundle row per {@code (legacy Location, referencing Club)} pair —
 * the referencing-Club set is the union of {@code Flights.StartLocationId
 * / LdgLocationId}, {@code Clubs.HomebaseId}, and {@code Aircrafts.HomebaseId}
 * joined through the Aircraft's computed {@code managing_club_id}. The
 * producer supplies {@code FanoutClubId} as an aliased column on the
 * cursor; this mapper reads it as {@code ClubId}.
 *
 * <p>{@code legacy_id_map_location} is composite-keyed
 * {@code (legacy_guid, club_id)} (S-141 temp-table change). FK rewrites
 * pick the replica matching the source's operating/managing club —
 * Aircraft.homebase_id falls back to lowest-UUID when the matching
 * replica is missing, deterministic-fan-out invariant aside.
 *
 * <p>Outgoing structural FKs:
 * <ul>
 *   <li>{@code club_id} → CLUB (the tenant discriminator per V7).</li>
 *   <li>{@code country_id} → COUNTRY (system-global cross-tenant ref).</li>
 * </ul>
 * Lookup FKs to {@code t_location_type}, {@code t_elevation_unit_type},
 * {@code t_length_unit_type} use V3's {@code legacy_int_id} seed lookup;
 * those entities live outside the {@link EntityType} enum so they are
 * resolved structurally by S-141 from the V3 seed table itself, not via a
 * per-bundle dependency declaration.
 *
 * <p>Legacy ASP.NET artifacts dropped: {@code OwnerId},
 * {@code OwnershipType}, {@code RecordState}, {@code IsDeleted}.
 */
public final class LocationMapper implements Mapper {

    static final String LEGACY_GUID = "legacy_guid";
    static final String CLUB_ID = "club_id";
    static final String LOCATION_NAME = "location_name";
    static final String LOCATION_SHORT_NAME = "location_short_name";
    static final String COUNTRY_ID = "country_id";
    static final String LOCATION_TYPE_ID = "location_type_id";
    static final String ICAO_CODE = "icao_code";
    static final String LATITUDE = "latitude";
    static final String LONGITUDE = "longitude";
    static final String ELEVATION = "elevation";
    static final String ELEVATION_UNIT_TYPE_ID = "elevation_unit_type_id";
    static final String RUNWAY_DIRECTION = "runway_direction";
    static final String RUNWAY_LENGTH = "runway_length";
    static final String RUNWAY_LENGTH_UNIT_TYPE_ID = "runway_length_unit_type_id";
    static final String AIRPORT_FREQUENCY = "airport_frequency";
    static final String DESCRIPTION = "description";
    static final String SORT_INDICATOR = "sort_indicator";
    static final String IS_INBOUND_ROUTE_REQUIRED = "is_inbound_route_required";
    static final String IS_OUTBOUND_ROUTE_REQUIRED = "is_outbound_route_required";
    static final String IS_FAST_ENTRY_RECORD = "is_fast_entry_record";

    @ParityIgnore
    static final String CREATED_ON = "created_on";
    static final String CREATED_BY_USER_ID = "created_by_user_id";
    @ParityIgnore
    static final String MODIFIED_ON = "modified_on";
    static final String MODIFIED_BY_USER_ID = "modified_by_user_id";
    static final String DELETED_ON = "deleted_on";
    static final String DELETED_BY_USER_ID = "deleted_by_user_id";

    private static final String[] COLUMNS = {
            LEGACY_GUID, CLUB_ID, LOCATION_NAME, LOCATION_SHORT_NAME,
            COUNTRY_ID, LOCATION_TYPE_ID,
            ICAO_CODE, LATITUDE, LONGITUDE,
            ELEVATION, ELEVATION_UNIT_TYPE_ID,
            RUNWAY_DIRECTION, RUNWAY_LENGTH, RUNWAY_LENGTH_UNIT_TYPE_ID,
            AIRPORT_FREQUENCY, DESCRIPTION, SORT_INDICATOR,
            IS_INBOUND_ROUTE_REQUIRED, IS_OUTBOUND_ROUTE_REQUIRED,
            IS_FAST_ENTRY_RECORD,
            CREATED_ON, CREATED_BY_USER_ID,
            MODIFIED_ON, MODIFIED_BY_USER_ID,
            DELETED_ON, DELETED_BY_USER_ID
    };

    @Override
    public EntityType entityType() {
        return EntityType.LOCATION;
    }

    @Override
    public String[] columns() {
        return COLUMNS.clone();
    }

    @Override
    public List<EntityType> foreignKeys() {
        return List.of(EntityType.CLUB, EntityType.COUNTRY);
    }

    @Override
    public void writeNdjson(ResultSet source, JsonGenerator target)
            throws SQLException, IOException {
        target.writeStartObject();
        target.writeStringField(LEGACY_GUID, source.getString("LocationId"));
        target.writeStringField(CLUB_ID, source.getString("ClubId"));
        target.writeStringField(LOCATION_NAME, source.getString("LocationName"));
        Coercions.writeOptionalString(target, LOCATION_SHORT_NAME,
                source.getString("LocationShortName"));
        target.writeStringField(COUNTRY_ID, source.getString("CountryId"));
        target.writeStringField(LOCATION_TYPE_ID,
                Coercions.legacyIntIdToUuidString(source.getInt("LocationTypeId")));
        Coercions.writeOptionalString(target, ICAO_CODE, source.getString("IcaoCode"));
        Coercions.writeOptionalString(target, LATITUDE, source.getString("Latitude"));
        Coercions.writeOptionalString(target, LONGITUDE, source.getString("Longitude"));
        Coercions.writeOptionalInt(target, ELEVATION,
                source.getObject("Elevation", Integer.class));
        Coercions.writeOptionalString(target, ELEVATION_UNIT_TYPE_ID,
                optionalLegacyIntIdAsUuid(source, "ElevationUnitType"));
        Coercions.writeOptionalString(target, RUNWAY_DIRECTION,
                source.getString("RunwayDirection"));
        Coercions.writeOptionalInt(target, RUNWAY_LENGTH,
                source.getObject("RunwayLength", Integer.class));
        Coercions.writeOptionalString(target, RUNWAY_LENGTH_UNIT_TYPE_ID,
                optionalLegacyIntIdAsUuid(source, "RunwayLengthUnitType"));
        Coercions.writeOptionalString(target, AIRPORT_FREQUENCY,
                source.getString("AirportFrequency"));
        Coercions.writeOptionalString(target, DESCRIPTION, source.getString("Description"));
        Coercions.writeOptionalInt(target, SORT_INDICATOR,
                source.getObject("SortIndicator", Integer.class));
        target.writeBooleanField(IS_INBOUND_ROUTE_REQUIRED,
                source.getBoolean("IsInboundRouteRequired"));
        target.writeBooleanField(IS_OUTBOUND_ROUTE_REQUIRED,
                source.getBoolean("IsOutboundRouteRequired"));
        target.writeBooleanField(IS_FAST_ENTRY_RECORD, source.getBoolean("IsFastEntryRecord"));
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
        int position = 1;
        target.setObject(position++, UUID.fromString(source.get(LEGACY_GUID).asText()));
        target.setObject(position++, UUID.fromString(source.get(CLUB_ID).asText()));
        target.setString(position++, source.get(LOCATION_NAME).asText());
        target.setString(position++, Coercions.readStringOrNull(source, LOCATION_SHORT_NAME));
        target.setObject(position++, UUID.fromString(source.get(COUNTRY_ID).asText()));
        target.setObject(position++, UUID.fromString(source.get(LOCATION_TYPE_ID).asText()));
        target.setString(position++, Coercions.readStringOrNull(source, ICAO_CODE));
        target.setString(position++, Coercions.readStringOrNull(source, LATITUDE));
        target.setString(position++, Coercions.readStringOrNull(source, LONGITUDE));
        target.setObject(position++, Coercions.readIntOrNull(source, ELEVATION));
        target.setObject(position++, Coercions.readUuidOrNull(source, ELEVATION_UNIT_TYPE_ID));
        target.setString(position++, Coercions.readStringOrNull(source, RUNWAY_DIRECTION));
        target.setObject(position++, Coercions.readIntOrNull(source, RUNWAY_LENGTH));
        target.setObject(position++,
                Coercions.readUuidOrNull(source, RUNWAY_LENGTH_UNIT_TYPE_ID));
        target.setString(position++, Coercions.readStringOrNull(source, AIRPORT_FREQUENCY));
        target.setString(position++, Coercions.readStringOrNull(source, DESCRIPTION));
        target.setObject(position++, Coercions.readIntOrNull(source, SORT_INDICATOR));
        target.setObject(position++, source.get(IS_INBOUND_ROUTE_REQUIRED).asBoolean());
        target.setObject(position++, source.get(IS_OUTBOUND_ROUTE_REQUIRED).asBoolean());
        target.setObject(position++, source.get(IS_FAST_ENTRY_RECORD).asBoolean());
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, CREATED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, CREATED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, MODIFIED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, MODIFIED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, DELETED_ON));
        target.setObject(position, Coercions.readUuidOrNull(source, DELETED_BY_USER_ID));
    }
}
