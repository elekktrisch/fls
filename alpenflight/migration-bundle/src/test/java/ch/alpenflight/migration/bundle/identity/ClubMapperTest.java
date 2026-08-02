package ch.alpenflight.migration.bundle.identity;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.AbstractMapperContractTest;
import ch.alpenflight.migration.bundle.EntityType;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
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

    @Test
    void modifiedOnFallsBackToCreatedOnForANeverModifiedClub() throws Exception {
        // J-0c T-19: a real legacy Club created-but-never-modified has
        // ModifiedOn NULL; t_club.modified_on is NOT NULL (audit invariant).
        // The producer must coalesce to CreatedOn so the NDJSON carries a
        // value rather than emitting null and 23502-ing at ingest.
        Map<String, Object> row = legacyRow(seededFaker());
        row.put("ModifiedOn", null);
        Timestamp createdOn = Timestamp.from(Instant.parse("2016-06-07T22:53:46Z"));
        row.put("CreatedOn", createdOn);

        JsonNode emitted = invokeWriteNdjson(mapper, row);

        assertThat(emitted.get("modified_on").asText())
                .as("never-modified Club's modified_on == its created_on")
                .isEqualTo("2016-06-07T22:53:46Z");
        assertThat(emitted.get("modified_on").isNull())
                .as("modified_on must NOT be emitted null — t_club.modified_on is NOT NULL")
                .isFalse();
    }

    @Test
    void registrationOperatorRecipientsPortCommaJoinedAndLowercased() throws Exception {
        Map<String, Object> row = legacyRow(seededFaker());
        row.put("SendTrialFlightRegistrationOperatorEmailTo",
                "Organiser@Club.CH; second@Club.ch  third@club.ch");
        row.put("SendPassengerFlightRegistrationOperatorEmailTo", "  Scenic@Club.CH  ");

        JsonNode emitted = invokeWriteNdjson(mapper, row);

        assertThat(emitted.get("send_trial_flight_registration_operator_email").asText())
                .as("PublicRegistrationMailer splits recipients on comma alone — a legacy "
                        + "semicolon/whitespace list must arrive comma-joined or the migrated "
                        + "club resolves to one unusable recipient and notifies nobody")
                .isEqualTo("organiser@club.ch,second@club.ch,third@club.ch");
        assertThat(emitted.get("send_passenger_flight_registration_operator_email").asText())
                .isEqualTo("scenic@club.ch");
    }

    @Test
    void unparseableRegistrationOperatorRecipientPortsVerbatimAndBlankPortsAsOptedOut()
            throws Exception {
        Map<String, Object> row = legacyRow(seededFaker());
        row.put("SendTrialFlightRegistrationOperatorEmailTo", "keine;  info@club.ch");
        row.put("SendPassengerFlightRegistrationOperatorEmailTo", "   ");

        JsonNode emitted = invokeWriteNdjson(mapper, row);

        assertThat(emitted.get("send_trial_flight_registration_operator_email").asText())
                .as("an address legacy never validated is the club's own configuration — "
                        + "it ports verbatim to surface at the edit form, never dropped here")
                .isEqualTo("keine,info@club.ch");
        assertThat(emitted.get("send_passenger_flight_registration_operator_email").isNull())
                .as("a whitespace-only legacy value is the opted-out state, not a recipient")
                .isTrue();
    }

}
