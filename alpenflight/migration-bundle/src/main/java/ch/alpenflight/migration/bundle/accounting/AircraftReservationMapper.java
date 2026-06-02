package ch.alpenflight.migration.bundle.accounting;

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
 * Tenant-scoped reservation aggregate root: legacy
 * {@code AircraftReservations.AircraftReservationId} →
 * {@code t_aircraft_reservation.id}. {@code operating_club_id} is the
 * {@code @TenantId} discriminator per V4.
 *
 * <p>Cross-tenant FKs declared in {@code tenantBypassFks}:
 * {@code aircraft_id} (cross-tenant Aircraft per S-185 / ADR 0008),
 * {@code pilot_person_id}, {@code second_crew_person_id} (cross-tenant
 * Person sub-map). {@code location_id} is tenant-scoped — resolves
 * through the composite {@code legacy_id_map_location} replica
 * selection (S-185 pattern; replica matches the reservation's
 * {@code operating_club_id}). No tenant bypass for location_id.
 *
 * <p>{@code reservation_range tstzrange} is GENERATED ALWAYS AS
 * STORED in V4 and so absent from {@link #columns()}; the GENERATED
 * column derives from {@code reservation_start} + {@code reservation_end}
 * which the mapper emits.
 *
 * <p>Empty-range degenerate ({@code Start == End}) passes through —
 * S-064 aggregate-side rejects on read. Mapper does not pre-empt.
 *
 * <p>Legacy NULL {@code PilotPersonId} (degenerate reservation with no
 * pilot) cannot satisfy the V4 {@code pilot_person_id NOT NULL} + FK
 * RESTRICT — producer drops the row + emits
 * {@code RESERVATION_NO_PILOT} warning. Mapper trusts the producer.
 *
 * <p>Legacy ASP.NET artifacts dropped: {@code OwnerId},
 * {@code OwnershipType}, {@code RecordState}, {@code IsDeleted}.
 * Legacy {@code Remarks} maps to the new {@code info} column.
 */
public final class AircraftReservationMapper implements Mapper {

    static final String LEGACY_GUID = "legacy_guid";
    static final String OPERATING_CLUB_ID = "operating_club_id";
    static final String AIRCRAFT_ID = "aircraft_id";
    static final String RESERVATION_START = "reservation_start";
    static final String RESERVATION_END = "reservation_end";
    static final String IS_ALL_DAY = "is_all_day";
    static final String PILOT_PERSON_ID = "pilot_person_id";
    static final String SECOND_CREW_PERSON_ID = "second_crew_person_id";
    static final String LOCATION_ID = "location_id";
    static final String RESERVATION_TYPE_ID = "reservation_type_id";
    static final String FLIGHT_TYPE_ID = "flight_type_id";
    static final String INFO = "info";

    @ParityIgnore
    static final String CREATED_ON = "created_on";
    static final String CREATED_BY_USER_ID = "created_by_user_id";
    @ParityIgnore
    static final String MODIFIED_ON = "modified_on";
    static final String MODIFIED_BY_USER_ID = "modified_by_user_id";
    static final String DELETED_ON = "deleted_on";
    static final String DELETED_BY_USER_ID = "deleted_by_user_id";

    private static final String[] COLUMNS = {
            LEGACY_GUID, OPERATING_CLUB_ID, AIRCRAFT_ID,
            RESERVATION_START, RESERVATION_END, IS_ALL_DAY,
            PILOT_PERSON_ID, SECOND_CREW_PERSON_ID, LOCATION_ID,
            RESERVATION_TYPE_ID, FLIGHT_TYPE_ID, INFO,
            CREATED_ON, CREATED_BY_USER_ID,
            MODIFIED_ON, MODIFIED_BY_USER_ID,
            DELETED_ON, DELETED_BY_USER_ID
    };

    @Override
    public EntityType entityType() {
        return EntityType.AIRCRAFT_RESERVATION;
    }

    @Override
    public String[] columns() {
        return COLUMNS.clone();
    }

    @Override
    public List<EntityType> foreignKeys() {
        return List.of(
                EntityType.CLUB,
                EntityType.AIRCRAFT,
                EntityType.PERSON,
                EntityType.LOCATION,
                EntityType.AIRCRAFT_RESERVATION_TYPE,
                EntityType.FLIGHT_TYPE);
    }

    @Override
    public void writeNdjson(ResultSet source, JsonGenerator target)
            throws SQLException, IOException {
        target.writeStartObject();
        target.writeStringField(LEGACY_GUID, source.getString("AircraftReservationId"));
        target.writeStringField(OPERATING_CLUB_ID, source.getString("ClubId"));
        target.writeStringField(AIRCRAFT_ID, source.getString("AircraftId"));
        Coercions.writeRequiredTimestamp(target, RESERVATION_START,
                source.getTimestamp("Start"));
        Coercions.writeRequiredTimestamp(target, RESERVATION_END,
                source.getTimestamp("End"));
        target.writeBooleanField(IS_ALL_DAY, source.getBoolean("IsAllDayReservation"));
        target.writeStringField(PILOT_PERSON_ID, source.getString("PilotPersonId"));
        Coercions.writeOptionalString(target, SECOND_CREW_PERSON_ID,
                source.getString("SecondCrewPersonId"));
        target.writeStringField(LOCATION_ID, source.getString("LocationId"));
        Coercions.writeOptionalString(target, RESERVATION_TYPE_ID,
                source.getString("AircraftReservationTypeId"));
        Coercions.writeOptionalString(target, FLIGHT_TYPE_ID, source.getString("FlightTypeId"));
        Coercions.writeOptionalString(target, INFO, source.getString("Remarks"));
        Coercions.writeRequiredTimestamp(target, CREATED_ON, source.getTimestamp("CreatedOn"));
        target.writeStringField(CREATED_BY_USER_ID, source.getString("CreatedByUserId"));
        Coercions.writeRequiredTimestampCoalescing(
                target, MODIFIED_ON, source.getTimestamp("ModifiedOn"),
                source.getTimestamp("CreatedOn"));
        Coercions.writeOptionalString(target, MODIFIED_BY_USER_ID,
                source.getString("ModifiedByUserId"));
        Coercions.writeOptionalTimestamp(target, DELETED_ON, source.getTimestamp("DeletedOn"));
        Coercions.writeOptionalString(target, DELETED_BY_USER_ID,
                source.getString("DeletedByUserId"));
        target.writeEndObject();
    }

    @Override
    public void readEntity(JsonNode source, PreparedStatement target) throws SQLException {
        int position = 1;
        target.setObject(position++, UUID.fromString(source.get(LEGACY_GUID).asText()));
        target.setObject(position++, UUID.fromString(source.get(OPERATING_CLUB_ID).asText()));
        target.setObject(position++, UUID.fromString(source.get(AIRCRAFT_ID).asText()));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, RESERVATION_START));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, RESERVATION_END));
        target.setObject(position++, source.get(IS_ALL_DAY).asBoolean());
        target.setObject(position++, UUID.fromString(source.get(PILOT_PERSON_ID).asText()));
        target.setObject(position++, Coercions.readUuidOrNull(source, SECOND_CREW_PERSON_ID));
        target.setObject(position++, UUID.fromString(source.get(LOCATION_ID).asText()));
        target.setObject(position++, Coercions.readUuidOrNull(source, RESERVATION_TYPE_ID));
        target.setObject(position++, Coercions.readUuidOrNull(source, FLIGHT_TYPE_ID));
        target.setString(position++, Coercions.readStringOrNull(source, INFO));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, CREATED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, CREATED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, MODIFIED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, MODIFIED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, DELETED_ON));
        target.setObject(position, Coercions.readUuidOrNull(source, DELETED_BY_USER_ID));
    }
}
