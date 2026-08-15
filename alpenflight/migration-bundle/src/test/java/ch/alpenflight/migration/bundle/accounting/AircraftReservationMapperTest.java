package ch.alpenflight.migration.bundle.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.AbstractMapperContractTest;
import ch.alpenflight.migration.bundle.EntityType;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import net.datafaker.Faker;
import org.junit.jupiter.api.Test;

class AircraftReservationMapperTest
        extends AbstractMapperContractTest<AircraftReservationMapper> {

    private final AircraftReservationMapper mapper = new AircraftReservationMapper();

    @Override
    protected AircraftReservationMapper mapper() {
        return mapper;
    }

    @Override
    protected Map<String, Object> legacyRow(Faker faker) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("AircraftReservationId", randomUuidString(faker));
        row.put("ClubId", randomUuidString(faker));
        row.put("AircraftId", randomUuidString(faker));
        row.put("Start", Timestamp.from(Instant.parse("2024-06-15T07:00:00Z")));
        row.put("End", Timestamp.from(Instant.parse("2024-06-15T11:00:00Z")));
        row.put("IsAllDayReservation", false);
        row.put("PilotPersonId", randomUuidString(faker));
        row.put("SecondCrewPersonId", randomUuidString(faker));
        row.put("LocationId", randomUuidString(faker));
        row.put("AircraftReservationTypeId", randomUuidString(faker));
        row.put("FlightTypeId", randomUuidString(faker));
        row.put("Remarks", faker.lorem().sentence());
        row.put("CreatedOn", Timestamp.from(Instant.parse("2024-01-01T12:00:00Z")));
        row.put("CreatedByUserId", randomUuidString(faker));
        row.put("ModifiedOn", Timestamp.from(Instant.parse("2024-02-01T12:00:00Z")));
        row.put("ModifiedByUserId", randomUuidString(faker));
        row.put("DeletedOn", Timestamp.from(Instant.parse("2024-03-01T12:00:00Z")));
        row.put("DeletedByUserId", randomUuidString(faker));
        return row;
    }

    @Test
    void exposesAircraftReservationEntityType() {
        assertThat(mapper.entityType()).isEqualTo(EntityType.AIRCRAFT_RESERVATION);
    }

    @Test
    void declaresCrossTenantAircraftAndPersonAsForeignKeys() {
        assertThat(mapper.foreignKeyTargets())
                .containsExactly(
                        EntityType.CLUB,
                        EntityType.AIRCRAFT,
                        EntityType.PERSON,
                        EntityType.LOCATION,
                        EntityType.AIRCRAFT_RESERVATION_TYPE,
                        EntityType.FLIGHT_TYPE);
    }

    @Test
    void declaresOffConventionForeignKeyColumnsSoFkResolutionRewritesThem() {
        assertThat(mapper.foreignKeyColumns())
                .extracting(fk -> fk.column() + "->" + fk.target()
                        + (fk.referencerClubColumn() == null ? "" : "@" + fk.referencerClubColumn()))
                .containsExactlyInAnyOrder(
                        "operating_club_id->" + EntityType.CLUB,
                        "pilot_person_id->" + EntityType.PERSON,
                        "second_crew_person_id->" + EntityType.PERSON,
                        "reservation_type_id->" + EntityType.AIRCRAFT_RESERVATION_TYPE,
                        "location_id->" + EntityType.LOCATION + "@operating_club_id");
    }

    @Test
    void columnsDoNotIncludeGeneratedReservationRange() {
        assertThat(mapper.wireColumns())
                .as("reservation_range is GENERATED ALWAYS AS STORED in V4 — "
                        + "binding it would conflict with the auto-derive at INSERT")
                .doesNotContain("reservation_range");
    }
}
