package ch.alpenflight.migration.bundle.flight;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.AbstractMapperContractTest;
import ch.alpenflight.migration.bundle.EntityType;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import net.datafaker.Faker;
import org.junit.jupiter.api.Test;

class FlightTypeMapperTest extends AbstractMapperContractTest<FlightTypeMapper> {

    private final FlightTypeMapper mapper = new FlightTypeMapper();

    @Override
    protected FlightTypeMapper mapper() {
        return mapper;
    }

    @Override
    protected Map<String, Object> legacyRow(Faker faker) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("FlightTypeId", randomUuidString(faker));
        row.put("ClubId", randomUuidString(faker));
        row.put("FlightTypeName", "Training " + faker.aviation().aircraft());
        row.put("FlightCode", faker.regexify("[A-Z]{2}-[0-9]{2}"));
        row.put("InstructorRequired", true);
        row.put("ObserverPilotOrInstructorRequired", false);
        row.put("IsCheckFlight", false);
        row.put("IsPassengerFlight", false);
        row.put("IsSoloFlight", false);
        row.put("IsForGliderFlights", true);
        row.put("IsForTowFlights", false);
        row.put("IsForMotorFlights", false);
        row.put("IsFlightCostBalanceSelectable", true);
        row.put("IsCouponNumberRequired", false);
        row.put("IsForAircraftReservationType", false);
        row.put("MinNrOfAircraftSeatsRequired", 1);
        row.put("CreatedOn", Timestamp.from(Instant.parse("2024-01-01T12:00:00Z")));
        row.put("CreatedByUserId", randomUuidString(faker));
        row.put("ModifiedOn", Timestamp.from(Instant.parse("2024-02-01T12:00:00Z")));
        row.put("ModifiedByUserId", randomUuidString(faker));
        row.put("DeletedOn", Timestamp.from(Instant.parse("2024-03-01T12:00:00Z")));
        row.put("DeletedByUserId", randomUuidString(faker));
        return row;
    }

    @Test
    void exposesFlightTypeEntityType() {
        assertThat(mapper.entityType()).isEqualTo(EntityType.FLIGHT_TYPE);
    }

    @Test
    void declaresClubAsTheOnlyStructuralForeignKey() {
        assertThat(mapper.foreignKeys()).containsExactly(EntityType.CLUB);
    }
}
