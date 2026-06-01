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
 * Aggregate-internal child of {@link LocationMapper}: legacy
 * {@code InOutboundPoints.InOutboundPointId} → {@code t_inoutbound_point.id}.
 * Represents an inbound/outbound routing waypoint at an airfield (V3
 * {@code t_inoutbound_point}, reclassified TENANT_SCOPED-by-parent under V7).
 *
 * <p><strong>Tenancy is inherited at rest, but the wire row must carry its own
 * {@code club_id} to be resolvable.</strong> Like {@link FlightCrewMapper} under
 * {@link FlightMapper}, the persisted {@code t_inoutbound_point} row has no own
 * {@code club_id} column — it inherits the tenant of its parent {@code Location}
 * through the {@code location_id} FK. But the parent {@code Location} is a
 * fan-out target keyed {@code (legacy_guid, club_id)}: the shared legacy GUID is
 * IDENTICAL across every replica, so {@code location_id} alone does NOT
 * disambiguate which per-club replica this child means. The child must therefore
 * carry its OWN legacy {@code club_id} on the wire (the producer fans the child
 * out too, one row per (legacy IOP, legacy club)); the
 * {@code ForeignKeyResolver} then keys the composite lookup on
 * {@code (location_id = legacy LocationId, club_id = child's own legacy club)} to
 * land on the matching replica. (J-0b T-01 records this contract shape; the
 * producer fan-out + composite resolution land in T-05/T-07.)
 *
 * <p>Column shape changes legacy → V3:
 * <ul>
 *   <li>{@code InOutboundPointName} → {@code point_name} (the waypoint label,
 *       e.g. {@code 07N}).</li>
 *   <li>Legacy {@code IsInboundPoint} / {@code IsOutboundPoint} bit pair →
 *       {@code direction} token ({@code INBOUND} / {@code OUTBOUND} /
 *       {@code INOUTBOUND}, null when neither set). The V3 schema models
 *       direction as free-text {@code VARCHAR(50)} rather than two bits.</li>
 *   <li>{@code point_type} / {@code description} have no legacy source — they
 *       are new-schema-only fields managed via the Location edit form, so they
 *       map to SQL NULL and are {@link ParityIgnore}d for the round-trip
 *       contract.</li>
 * </ul>
 *
 * <p>Legacy ASP.NET artifacts dropped: {@code OwnerId}, {@code OwnershipType},
 * {@code RecordState}, {@code IsDeleted}.
 */
public final class InOutboundPointMapper implements Mapper {

    static final String LEGACY_GUID = "legacy_guid";
    static final String LOCATION_ID = "location_id";
    static final String POINT_NAME = "point_name";
    static final String DIRECTION = "direction";

    @ParityIgnore
    static final String POINT_TYPE = "point_type";
    @ParityIgnore
    static final String DESCRIPTION = "description";

    @ParityIgnore
    static final String CREATED_ON = "created_on";
    static final String CREATED_BY_USER_ID = "created_by_user_id";
    @ParityIgnore
    static final String MODIFIED_ON = "modified_on";
    static final String MODIFIED_BY_USER_ID = "modified_by_user_id";
    static final String DELETED_ON = "deleted_on";
    static final String DELETED_BY_USER_ID = "deleted_by_user_id";

    private static final String[] COLUMNS = {
            LEGACY_GUID, LOCATION_ID, POINT_NAME, POINT_TYPE, DIRECTION, DESCRIPTION,
            CREATED_ON, CREATED_BY_USER_ID,
            MODIFIED_ON, MODIFIED_BY_USER_ID,
            DELETED_ON, DELETED_BY_USER_ID
    };

    @Override
    public EntityType entityType() {
        return EntityType.INOUTBOUND_POINT;
    }

    @Override
    public String[] columns() {
        return COLUMNS.clone();
    }

    @Override
    public List<EntityType> foreignKeys() {
        // Parent Location only — tenancy is inherited through it; there is no
        // own club_id. The FK target must precede INOUTBOUND_POINT in the
        // EntityType topo order so the parent fan-out replicas exist first.
        return List.of(EntityType.LOCATION);
    }

    @Override
    public void writeNdjson(ResultSet source, JsonGenerator target)
            throws SQLException, IOException {
        target.writeStartObject();
        target.writeStringField(LEGACY_GUID, source.getString("InOutboundPointId"));
        target.writeStringField(LOCATION_ID, source.getString("LocationId"));
        target.writeStringField(POINT_NAME, source.getString("InOutboundPointName"));
        // No legacy source — managed only via the new Location edit form.
        target.writeNullField(POINT_TYPE);
        Coercions.writeOptionalString(target, DIRECTION,
                directionToken(source.getBoolean("IsInboundPoint"),
                        source.getBoolean("IsOutboundPoint")));
        target.writeNullField(DESCRIPTION);
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

    @Override
    public void readEntity(JsonNode source, PreparedStatement target) throws SQLException {
        int position = 1;
        target.setObject(position++, UUID.fromString(source.get(LEGACY_GUID).asText()));
        target.setObject(position++, UUID.fromString(source.get(LOCATION_ID).asText()));
        target.setString(position++, source.get(POINT_NAME).asText());
        target.setString(position++, Coercions.readStringOrNull(source, POINT_TYPE));
        target.setString(position++, Coercions.readStringOrNull(source, DIRECTION));
        target.setString(position++, Coercions.readStringOrNull(source, DESCRIPTION));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, CREATED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, CREATED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, MODIFIED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, MODIFIED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, DELETED_ON));
        target.setObject(position, Coercions.readUuidOrNull(source, DELETED_BY_USER_ID));
    }

    /**
     * Collapse the legacy {@code IsInboundPoint} / {@code IsOutboundPoint} bit
     * pair into the V3 {@code direction} token. Null (neither bit set) leaves
     * {@code direction} SQL-NULL; legacy data always sets at least one.
     */
    private static String directionToken(boolean inbound, boolean outbound) {
        if (inbound && outbound) {
            return "INOUTBOUND";
        }
        if (inbound) {
            return "INBOUND";
        }
        if (outbound) {
            return "OUTBOUND";
        }
        return null;
    }
}
