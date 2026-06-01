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
 * <p><strong>Tenancy is inherited at rest, but the wire row carries its own
 * {@code club_id} as a RESOLVER-ONLY field.</strong> The persisted
 * {@code t_inoutbound_point} row has no own {@code club_id} column — it inherits
 * the tenant of its parent {@code Location} through the {@code location_id} FK.
 * But the parent {@code Location} is a fan-out target keyed
 * {@code (legacy_guid, club_id)}: the shared legacy GUID is IDENTICAL across
 * every replica, so {@code location_id} alone does NOT disambiguate which
 * per-club replica this child means. The child therefore fans out too (one row
 * per {@code (legacy IOP, partner club)}, joining its parent Location's fan-out
 * partner set) and carries its OWN legacy {@code club_id} on the wire so T-07's
 * {@code ForeignKeyResolver} can key the composite lookup on
 * {@code (location_id = legacy LocationId, club_id = child's own legacy club)}
 * and land on the matching replica.
 *
 * <p><strong>Wire vs. destination asymmetry (J-0b T-05).</strong> The
 * {@code club_id} wire field is consumed by the resolver and never persisted —
 * it is deliberately ABSENT from {@link #columns()} (no destination column).
 * This is a genuine first: unlike every other mapper, the wire shape is a
 * superset of {@code columns()}. The shared contract test only enforces
 * {@code columns() ⊆ wire} (every declared column is emitted) and never
 * {@code wire ⊆ columns()}, so the asymmetry needs no test weakening. Because
 * one legacy IOP now lands as N replicas, {@code id} is minted distinct per
 * replica via {@link Coercions#deriveFanOutId}{@code (legacy IOP id, legacy
 * club id)} (else the N replicas would PK-collide on {@code t_inoutbound_point.id}),
 * while {@code legacy_guid} stays the shared legacy IOP id and {@code location_id}
 * stays the shared legacy parent LocationId. T-06 (ingest de-aliasing for the
 * fan-out {@code id}/{@code legacy_guid} split) + T-07 (composite FK resolution)
 * complete the round trip; T-08 greens the proof IT.
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

    static final String ID = "id";
    static final String LEGACY_GUID = "legacy_guid";
    static final String LOCATION_ID = "location_id";
    /**
     * Resolver-only wire field — the child's OWN legacy club id, emitted by
     * {@link #writeNdjson} so T-07's {@code ForeignKeyResolver} can key the
     * composite {@code (location_id, club_id)} lookup, but DELIBERATELY ABSENT
     * from {@link #columns()}: {@code t_inoutbound_point} has no {@code club_id}
     * column (tenancy is inherited via {@code location_id}). The wire/INSERT
     * asymmetry is intentional — see the class Javadoc.
     */
    static final String CLUB_ID = "club_id";
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

    // CLUB_ID is intentionally NOT here — it is a resolver-only wire field, not
    // a destination column (t_inoutbound_point has no club_id). ID prepends the
    // list as a real destination column now (mirroring LocationMapper); the
    // ingest INSERT carries both id and legacy_guid as separate columns.
    private static final String[] COLUMNS = {
            ID, LEGACY_GUID, LOCATION_ID, POINT_NAME, POINT_TYPE, DIRECTION, DESCRIPTION,
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
        UUID legacyIopId = UUID.fromString(source.getString("InOutboundPointId"));
        UUID legacyClubId = UUID.fromString(source.getString("ClubId"));
        // Fan-out (J-0b): the child fans out one row per (legacy IOP, partner
        // club) — the same partner set its parent Location fans out across. Each
        // replica gets a distinct id derived from the LEGACY club id (the only id
        // stable producer↔referencer; ingest never sees it), mirroring the parent.
        // legacy_guid stays the shared IOP id and location_id stays the shared
        // legacy parent LocationId — T-07 resolves the parent composite via the
        // resolver-only club_id below.
        target.writeStringField(ID,
                Coercions.deriveFanOutId(legacyIopId, legacyClubId).toString());
        target.writeStringField(LEGACY_GUID, legacyIopId.toString());
        target.writeStringField(LOCATION_ID, source.getString("LocationId"));
        // Resolver-only: emitted on the wire for T-07's composite FK lookup, NOT
        // a destination column (absent from columns(); t_inoutbound_point has no
        // club_id — tenancy inherited via location_id).
        target.writeStringField(CLUB_ID, legacyClubId.toString());
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
        // id (the derived per-replica PK) and legacy_guid (the shared legacy IOP
        // id) are SEPARATE destination columns per the J-0b fan-out design — no
        // legacy_guid → id alias. club_id is NOT read: it is a resolver-only wire
        // field with no destination column.
        target.setObject(position++, UUID.fromString(source.get(ID).asText()));
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
