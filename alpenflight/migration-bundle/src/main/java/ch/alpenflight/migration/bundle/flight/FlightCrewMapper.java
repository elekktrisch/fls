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
 * Aggregate-internal crew row under {@link FlightMapper}: legacy
 * {@code FlightCrew.FlightCrewId} → {@code t_flight_crew.id}. Highest-row
 * entity in the system (~25M rows at customer scale per ADR 0019).
 *
 * <p><strong>S-188 JMH-benched mapper.</strong> Allocation discipline per
 * the {@link Mapper} contract: no per-row allocation beyond Jackson +
 * JDBC inherent. No {@code String.format}, no defensive byte copies, no
 * intermediate {@code Map}/buffer reuse, no boxed-Integer FK paths.
 * Budget per S-183 / S-188: ≥ 200K rows/sec single-thread, ≤ 50 MB/s
 * allocation rate (-prof gc).
 *
 * <p>{@code person_id} → cross-tenant Person (tenant-bypass per the
 * {@link ch.alpenflight.migration.bundle.Manifest} TENANT_BYPASS_ALLOW_LIST
 * widening). {@code flight_crew_type_id} resolves through V3's seeded
 * {@code t_flight_crew_type.legacy_int_id} map — entity outside
 * {@link EntityType}, no per-bundle dependency declared.
 *
 * <p>Legacy ASP.NET artifacts dropped: {@code OwnerId},
 * {@code OwnershipType}, {@code RecordState}, {@code IsDeleted}. V3
 * destination keeps only {@code deleted_on} / {@code deleted_by_user_id}
 * — no created/modified audit pair on this aggregate-internal row.
 */
public final class FlightCrewMapper implements Mapper {

    static final String LEGACY_GUID = "legacy_guid";
    static final String FLIGHT_ID = "flight_id";
    static final String PERSON_ID = "person_id";
    static final String FLIGHT_CREW_TYPE_ID = "flight_crew_type_id";
    static final String BEGIN_FLIGHT_DATETIME = "begin_flight_datetime";
    static final String END_FLIGHT_DATETIME = "end_flight_datetime";
    static final String BEGIN_INSTRUCTION_DATETIME = "begin_instruction_datetime";
    static final String END_INSTRUCTION_DATETIME = "end_instruction_datetime";
    static final String NR_OF_LDGS = "nr_of_ldgs";
    static final String NR_OF_STARTS = "nr_of_starts";
    static final String DELETED_ON = "deleted_on";

    @ParityIgnore
    static final String DELETED_BY_USER_ID = "deleted_by_user_id";

    private static final String[] COLUMNS = {
            LEGACY_GUID, FLIGHT_ID, PERSON_ID, FLIGHT_CREW_TYPE_ID,
            BEGIN_FLIGHT_DATETIME, END_FLIGHT_DATETIME,
            BEGIN_INSTRUCTION_DATETIME, END_INSTRUCTION_DATETIME,
            NR_OF_LDGS, NR_OF_STARTS,
            DELETED_ON, DELETED_BY_USER_ID
    };

    @Override
    public EntityType entityType() {
        return EntityType.FLIGHT_CREW;
    }

    @Override
    public String[] columns() {
        return COLUMNS.clone();
    }

    @Override
    public List<EntityType> foreignKeys() {
        return List.of(EntityType.FLIGHT, EntityType.PERSON);
    }

    @Override
    public void writeNdjson(ResultSet source, JsonGenerator target)
            throws SQLException, IOException {
        target.writeStartObject();
        target.writeStringField(LEGACY_GUID, source.getString("FlightCrewId"));
        target.writeStringField(FLIGHT_ID, source.getString("FlightId"));
        target.writeStringField(PERSON_ID, source.getString("PersonId"));
        target.writeStringField(FLIGHT_CREW_TYPE_ID,
                Coercions.legacyIntIdToUuidString(source.getInt("FlightCrewType")));
        Coercions.writeOptionalTimestamp(target, BEGIN_FLIGHT_DATETIME,
                source.getTimestamp("BeginFlightDateTime"));
        Coercions.writeOptionalTimestamp(target, END_FLIGHT_DATETIME,
                source.getTimestamp("EndFlightDateTime"));
        Coercions.writeOptionalTimestamp(target, BEGIN_INSTRUCTION_DATETIME,
                source.getTimestamp("BeginInstructionDateTime"));
        Coercions.writeOptionalTimestamp(target, END_INSTRUCTION_DATETIME,
                source.getTimestamp("EndInstructionDateTime"));
        Coercions.writeOptionalShort(target, NR_OF_LDGS,
                source.getObject("NrOfLdgs", Short.class));
        Coercions.writeOptionalShort(target, NR_OF_STARTS,
                source.getObject("NrOfStarts", Short.class));
        Coercions.writeOptionalTimestamp(target, DELETED_ON, source.getTimestamp("DeletedOn"));
        Coercions.writeOptionalString(target, DELETED_BY_USER_ID,
                source.getString("DeletedByUserId"));
        target.writeEndObject();
    }

    @Override
    public void readEntity(JsonNode source, PreparedStatement target) throws SQLException {
        int position = 1;
        target.setObject(position++, UUID.fromString(source.get(LEGACY_GUID).asText()));
        target.setObject(position++, UUID.fromString(source.get(FLIGHT_ID).asText()));
        target.setObject(position++, UUID.fromString(source.get(PERSON_ID).asText()));
        target.setObject(position++,
                UUID.fromString(source.get(FLIGHT_CREW_TYPE_ID).asText()));
        target.setTimestamp(position++,
                Coercions.readTimestampOrNull(source, BEGIN_FLIGHT_DATETIME));
        target.setTimestamp(position++,
                Coercions.readTimestampOrNull(source, END_FLIGHT_DATETIME));
        target.setTimestamp(position++,
                Coercions.readTimestampOrNull(source, BEGIN_INSTRUCTION_DATETIME));
        target.setTimestamp(position++,
                Coercions.readTimestampOrNull(source, END_INSTRUCTION_DATETIME));
        Coercions.bindShortOrNull(target, position++, source, NR_OF_LDGS);
        Coercions.bindShortOrNull(target, position++, source, NR_OF_STARTS);
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, DELETED_ON));
        target.setObject(position, Coercions.readUuidOrNull(source, DELETED_BY_USER_ID));
    }
}
