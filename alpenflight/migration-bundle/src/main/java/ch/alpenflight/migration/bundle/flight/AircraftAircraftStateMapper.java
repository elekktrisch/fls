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
 * Aircraft↔state history, aggregate-internal under {@code Aircraft}
 * (cross-tenant). Legacy composite PK
 * {@code (AircraftId, AircraftStateId, ValidFrom)} → surrogate
 * {@code id UUID PK} minted by S-141 INSERT (V3 reshape rationale in
 * {@code V3__flights_aircraft_locations.sql}).
 *
 * <p>No own {@code legacy_id_map_*} temp table — leaf entity, nobody FKs
 * back into it. Mapper carries only the wire columns; S-141 mints the
 * surrogate at INSERT time (UUID v7).
 *
 * <p>{@code noticed_by_person_id} → cross-tenant Person; declared in the
 * {@link ch.alpenflight.migration.bundle.Manifest} TENANT_BYPASS_ALLOW_LIST
 * widening. {@code aircraft_state_id} resolves through V3's seeded
 * {@code t_aircraft_state.legacy_int_id} map — entity outside
 * {@link EntityType}, not a per-bundle dependency.
 *
 * <p>Legacy ASP.NET artifacts dropped: {@code OwnerId},
 * {@code OwnershipType}, {@code RecordState}. Legacy schema lacks an
 * {@code IsDeleted} column on this table — the new {@code deleted_on}
 * tombstone column is populated only on subsequent post-migration
 * mutations; ports as NULL.
 */
public final class AircraftAircraftStateMapper implements Mapper {

    static final String AIRCRAFT_ID = "aircraft_id";
    static final String AIRCRAFT_STATE_ID = "aircraft_state_id";
    static final String VALID_FROM = "valid_from";
    static final String VALID_TO = "valid_to";
    static final String NOTICED_BY_PERSON_ID = "noticed_by_person_id";
    static final String REMARKS = "remarks";

    @ParityIgnore
    static final String CREATED_ON = "created_on";
    static final String CREATED_BY_USER_ID = "created_by_user_id";
    @ParityIgnore
    static final String MODIFIED_ON = "modified_on";
    static final String MODIFIED_BY_USER_ID = "modified_by_user_id";
    static final String DELETED_ON = "deleted_on";
    static final String DELETED_BY_USER_ID = "deleted_by_user_id";

    private static final String[] COLUMNS = {
            AIRCRAFT_ID, AIRCRAFT_STATE_ID, VALID_FROM, VALID_TO,
            NOTICED_BY_PERSON_ID, REMARKS,
            CREATED_ON, CREATED_BY_USER_ID,
            MODIFIED_ON, MODIFIED_BY_USER_ID,
            DELETED_ON, DELETED_BY_USER_ID
    };

    @Override
    public EntityType entityType() {
        return EntityType.AIRCRAFT_AIRCRAFT_STATE;
    }

    @Override
    public String[] columns() {
        return COLUMNS.clone();
    }

    @Override
    public List<EntityType> foreignKeys() {
        return List.of(EntityType.AIRCRAFT);
    }

    @Override
    public void writeNdjson(ResultSet source, JsonGenerator target)
            throws SQLException, IOException {
        target.writeStartObject();
        target.writeStringField(AIRCRAFT_ID, source.getString("AircraftId"));
        target.writeStringField(AIRCRAFT_STATE_ID,
                Coercions.legacyIntIdToUuidString(source.getInt("AircraftState")));
        Coercions.writeRequiredTimestamp(target, VALID_FROM, source.getTimestamp("ValidFrom"));
        Coercions.writeOptionalTimestamp(target, VALID_TO, source.getTimestamp("ValidTo"));
        Coercions.writeOptionalString(target, NOTICED_BY_PERSON_ID,
                source.getString("NoticedByPersonId"));
        Coercions.writeOptionalString(target, REMARKS, source.getString("Remarks"));
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
        target.setObject(position++, UUID.fromString(source.get(AIRCRAFT_ID).asText()));
        target.setObject(position++, UUID.fromString(source.get(AIRCRAFT_STATE_ID).asText()));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, VALID_FROM));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, VALID_TO));
        target.setObject(position++, Coercions.readUuidOrNull(source, NOTICED_BY_PERSON_ID));
        target.setString(position++, Coercions.readStringOrNull(source, REMARKS));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, CREATED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, CREATED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, MODIFIED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, MODIFIED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, DELETED_ON));
        target.setObject(position, Coercions.readUuidOrNull(source, DELETED_BY_USER_ID));
    }
}
