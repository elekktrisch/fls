package ch.alpenflight.migration.bundle.identity;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.AbstractMapperContractTest;
import ch.alpenflight.migration.bundle.EntityType;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.datafaker.Faker;
import org.junit.jupiter.api.Test;

class PersonClubMapperTest extends AbstractMapperContractTest<PersonClubMapper> {

    private final PersonClubMapper mapper = new PersonClubMapper();

    @Override
    protected PersonClubMapper mapper() {
        return mapper;
    }

    @Override
    protected Map<String, Object> legacyRow(Faker faker) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("PersonId", randomUuidString(faker));
        row.put("ClubId", randomUuidString(faker));
        row.put("MemberNumber", faker.expression("#{regexify '[0-9]{4,6}'}"));
        row.put("MemberStateId", randomUuidString(faker));
        row.put("IsMotorPilot", true);
        row.put("IsTowPilot", false);
        row.put("IsGliderInstructor", true);
        row.put("IsGliderPilot", true);
        row.put("IsGliderTrainee", false);
        row.put("IsPassenger", true);
        row.put("IsWinchOperator", false);
        row.put("IsMotorInstructor", true);
        row.put("ReceiveFlightReports", true);
        row.put("ReceiveAircraftReservationNotifications", false);
        row.put("ReceivePlanningDayRoleReminder", true);
        row.put("IsActive", true);
        row.put("CreatedOn", Timestamp.from(Instant.parse("2024-01-01T12:00:00Z")));
        row.put("CreatedByUserId", randomUuidString(faker));
        row.put("ModifiedOn", Timestamp.from(Instant.parse("2024-02-01T12:00:00Z")));
        row.put("ModifiedByUserId", randomUuidString(faker));
        row.put("DeletedOn", Timestamp.from(Instant.parse("2024-03-01T12:00:00Z")));
        row.put("DeletedByUserId", randomUuidString(faker));
        return row;
    }

    @Test
    void exposesPersonClubEntityType() {
        assertThat(mapper.entityType()).isEqualTo(EntityType.PERSON_CLUB);
    }

    @Test
    void declaresPersonClubMemberStateAsStructuralForeignKeys() {
        assertThat(mapper.foreignKeys())
                .containsExactly(EntityType.PERSON, EntityType.CLUB, EntityType.MEMBER_STATE);
    }

    @Test
    void columnsStartWithNaturalKeyPairAndOmitSurrogateId() {
        assertThat(mapper.columns())
                .as("PersonClub is a leaf junction — no legacy_id_map_person_club; "
                        + "S-141 mints UUID v7 for the surrogate id at INSERT.")
                .startsWith(PersonClubMapper.PERSON_ID, PersonClubMapper.CLUB_ID)
                .doesNotContain("id", "legacy_guid");
    }

    private static String randomUuidString(Faker faker) {
        return new UUID(faker.random().nextLong(), faker.random().nextLong()).toString();
    }
}
