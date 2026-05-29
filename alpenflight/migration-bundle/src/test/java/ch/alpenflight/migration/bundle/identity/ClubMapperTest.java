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

class ClubMapperTest extends AbstractMapperContractTest<ClubMapper> {

    private final ClubMapper mapper = new ClubMapper();

    @Override
    protected ClubMapper mapper() {
        return mapper;
    }

    @Override
    protected Map<String, Object> legacyRow(Faker faker) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ClubId", randomUuidString(faker));
        row.put("Clubname", faker.company().name());
        row.put("ClubKey", faker.expression("#{regexify '[A-Z]{4,8}'}"));
        row.put("Address", faker.address().streetAddress());
        row.put("Zip", faker.address().zipCode());
        row.put("City", faker.address().city());
        row.put("CountryId", randomUuidString(faker));
        row.put("Phone", faker.phoneNumber().phoneNumber());
        row.put("FaxNumber", faker.phoneNumber().phoneNumber());
        row.put("Email", faker.internet().emailAddress());
        row.put("WebPage", "https://example.test/" + faker.internet().slug());
        row.put("Contact", faker.name().fullName());
        row.put("ClubStateId", faker.random().nextInt(1, 1_000_000));
        row.put("SendAircraftStatisticReportTo", faker.internet().emailAddress());
        row.put("SendPlanningDayInfoMailTo", faker.internet().emailAddress());
        row.put("SendDeliveryMailExportTo", faker.internet().emailAddress());
        row.put("SendTrialFlightRegistrationOperatorEmailTo", faker.internet().emailAddress());
        row.put("SendPassengerFlightRegistrationOperatorEmailTo", faker.internet().emailAddress());
        row.put("ReplyToEmailAddress", faker.internet().emailAddress());
        row.put("RunDeliveryCreationJob", true);
        row.put("RunDeliveryMailExportJob", false);
        row.put("LastPersonSynchronisationOn", Timestamp.from(Instant.parse("2024-04-01T12:00:00Z")));
        row.put("LastDeliverySynchronisationOn", Timestamp.from(Instant.parse("2024-05-01T12:00:00Z")));
        row.put("LastArticleSynchronisationOn", Timestamp.from(Instant.parse("2024-06-01T12:00:00Z")));
        row.put("IsClubMemberNumberReadonly", true);
        row.put("CreatedOn", Timestamp.from(Instant.parse("2024-01-01T12:00:00Z")));
        row.put("CreatedByUserId", randomUuidString(faker));
        row.put("ModifiedOn", Timestamp.from(Instant.parse("2024-02-01T12:00:00Z")));
        row.put("ModifiedByUserId", randomUuidString(faker));
        row.put("DeletedOn", Timestamp.from(Instant.parse("2024-03-01T12:00:00Z")));
        row.put("DeletedByUserId", randomUuidString(faker));
        return row;
    }

    @Test
    void exposesClubEntityType() {
        assertThat(mapper.entityType()).isEqualTo(EntityType.CLUB);
    }

    @Test
    void declaresCountryAndClubStateAsStructuralFks() {
        assertThat(mapper.foreignKeys())
                .containsExactly(EntityType.COUNTRY, EntityType.CLUB_STATE);
    }

}
