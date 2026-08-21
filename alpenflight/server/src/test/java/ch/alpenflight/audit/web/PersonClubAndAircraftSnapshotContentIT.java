package ch.alpenflight.audit.web;

import static ch.alpenflight.audit.web.AuditTestSupport.parseSnapshot;
import static ch.alpenflight.audit.web.AuditTestSupport.preCleanAuditRowsThatOutliveTestRollback;
import static ch.alpenflight.audit.web.AuditTestSupport.suffix;
import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.audit.application.PiiRedactor;
import ch.alpenflight.platform.id.PersonId;
import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class PersonClubAndAircraftSnapshotContentIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID TENANT = UUID.fromString("019e30c3-2c00-7001-8000-000000000001");
    private static final String SEED_AIRCRAFT_TYPE_GLIDER = "019e2e15-2c00-7af9-8000-000000002af9";
    private static final String SEED_AIRCRAFT_STATE_OK = "019e2e15-2c00-7ee0-8000-000000002ee0";
    private static final String REDACTED = PiiRedactor.REDACTED_SENTINEL;

    private static final String PRIVATE_EMAIL_THAT_MUST_NEVER_REACH_THE_AUDIT_TRAIL =
            "ada.private@example.test";
    private static final String STATE_REMARK_THAT_MUST_STAY_REDACTED =
            "wing spar inspection booked with the mechanic";

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;

    private String clubAdminToken;

    @BeforeEach
    void mintClubAdminToken() {
        clubAdminToken = jwts.mint(c -> c
                .claim("clubId", TENANT.toString())
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR"))));
    }

    @Test
    void attaching_a_person_to_the_club_records_the_membership_not_an_empty_row() {
        String personId = createPersonWithoutMembership();
        preCleanAuditRowsThatOutliveTestRollback(jdbc, TENANT);

        String memberNumber = "M-" + shortMemberNumberSuffix();
        ResponseEntity<String> attached = post("/api/v1/persons/" + personId + "/clubs",
                membershipPayload(memberNumber, true, false));
        assertThat(attached.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> row = singleRowForAction("CREATE");
        assertThat(row.get("target_entity_type")).isEqualTo("PersonClub");

        JsonNode after = parseSnapshot(row.get("after_state"));
        assertThat(after.get("memberNumber").asText()).isEqualTo(memberNumber);
        assertThat(after.get("isGliderPilot").asBoolean()).isTrue();
        assertThat(after.get("isTowPilot").asBoolean()).isFalse();
        assertThat(after.get("isActive").asBoolean()).isTrue();
        assertThat(after.get("clubId").asText()).isEqualTo(TENANT.toString());
        assertThat(after.get("id").asText()).isEqualTo(row.get("target_entity_id").toString());
        assertNoFieldRendersTheRedactedSentinel(after);
        assertCarriesNoPersonIdentity(after);
    }

    @Test
    void updating_the_club_membership_records_the_change_on_both_sides() {
        String personId = createPersonWithoutMembership();
        String beforeMemberNumber = "M-" + shortMemberNumberSuffix();
        post("/api/v1/persons/" + personId + "/clubs",
                membershipPayload(beforeMemberNumber, true, false));
        preCleanAuditRowsThatOutliveTestRollback(jdbc, TENANT);

        String afterMemberNumber = "M-" + shortMemberNumberSuffix();
        ResponseEntity<String> updated = put("/api/v1/persons/" + personId + "/clubs/current",
                membershipPayload(afterMemberNumber, false, true));
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> row = singleRowForAction("UPDATE");
        assertThat(row.get("target_entity_type")).isEqualTo("PersonClub");

        JsonNode before = parseSnapshot(row.get("before_state"));
        JsonNode after = parseSnapshot(row.get("after_state"));
        assertThat(before.get("memberNumber").asText()).isEqualTo(beforeMemberNumber);
        assertThat(after.get("memberNumber").asText()).isEqualTo(afterMemberNumber);
        assertThat(before.get("isGliderPilot").asBoolean()).isTrue();
        assertThat(after.get("isGliderPilot").asBoolean()).isFalse();
        assertThat(before.get("isTowPilot").asBoolean()).isFalse();
        assertThat(after.get("isTowPilot").asBoolean()).isTrue();
        assertNoFieldRendersTheRedactedSentinel(before);
        assertNoFieldRendersTheRedactedSentinel(after);
        assertCarriesNoPersonIdentity(before);
        assertCarriesNoPersonIdentity(after);
    }

    @Test
    void leaving_the_club_records_the_membership_as_it_was_before_the_delete() {
        String personId = createPersonWithoutMembership();
        String memberNumber = "M-" + shortMemberNumberSuffix();
        post("/api/v1/persons/" + personId + "/clubs",
                membershipPayload(memberNumber, true, false));
        preCleanAuditRowsThatOutliveTestRollback(jdbc, TENANT);

        ResponseEntity<Void> left = rest.exchange(authed(
                        RequestEntity.delete(
                                URI.create("/api/v1/persons/" + personId + "/clubs/current")))
                        .build(),
                Void.class);
        assertThat(left.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        Map<String, Object> row = singleRowForAction("DELETE");
        assertThat(row.get("target_entity_type")).isEqualTo("PersonClub");
        assertThat(row.get("after_state")).as("a DELETE has no after-state").isNull();

        JsonNode before = parseSnapshot(row.get("before_state"));
        assertThat(before.get("memberNumber").asText()).isEqualTo(memberNumber);
        assertThat(before.get("isGliderPilot").asBoolean()).isTrue();
        assertThat(before.get("isActive"))
                .as("the DELETE snapshot shows the membership as it was BEFORE the leave, "
                        + "so it is still active: %s", before)
                .isNotNull();
        assertThat(before.get("isActive").asBoolean()).isTrue();
        assertNoFieldRendersTheRedactedSentinel(before);
        assertCarriesNoPersonIdentity(before);
    }

    @Test
    void a_person_write_stays_fully_redacted_because_person_is_deny_all() {
        preCleanAuditRowsThatOutliveTestRollback(jdbc, TENANT);
        String personId = createPersonWithoutMembership();

        Map<String, Object> row = singleRowForAction("CREATE");
        assertThat(row.get("target_entity_type")).isEqualTo("Person");
        assertThat(row.get("target_entity_id").toString())
                .isEqualTo(PersonId.parse(personId).value().toString());

        JsonNode after = parseSnapshot(row.get("after_state"));
        assertThat(after.fieldNames()).toIterable()
                .as("the Person snapshot still carries its field names")
                .contains("firstname", "lastname", "emailPrivate", "birthday", "privatePhone");
        after.fieldNames().forEachRemaining(field ->
                assertThat(after.get(field).asText())
                        .as("Person is deny-all, so %s must render the redaction sentinel", field)
                        .isEqualTo(REDACTED));
        assertThat(after.toString())
                .as("no Person identity value leaks into the audit row")
                .doesNotContain(PRIVATE_EMAIL_THAT_MUST_NEVER_REACH_THE_AUDIT_TRAIL)
                .doesNotContain("Lovelace");
    }

    @Test
    void an_aircraft_state_change_records_the_new_state_period_and_redacts_its_remarks() {
        String aircraftId = registerAircraft();
        preCleanAuditRowsThatOutliveTestRollback(jdbc, TENANT);

        Instant validFrom = Instant.parse("2026-03-01T08:00:00Z");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("aircraftStateId", SEED_AIRCRAFT_STATE_OK);
        payload.put("validFrom", validFrom.toString());
        payload.put("remarks", STATE_REMARK_THAT_MUST_STAY_REDACTED);
        ResponseEntity<String> res =
                post("/api/v1/aircraft/" + aircraftId + "/states", payload);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map<String, Object> row = singleRowForAction("STATE_TRANSITION");
        assertThat(row.get("target_entity_type")).isEqualTo("Aircraft");

        JsonNode after = parseSnapshot(row.get("after_state"));
        assertThat(after.get("aircraftStateId").asText()).isEqualTo(SEED_AIRCRAFT_STATE_OK);
        assertThat(after.get("validFrom").asText()).isEqualTo(validFrom.toString());
        assertThat(after.get("id").asText()).isEqualTo(readJson(res).get("id").asText());
        assertThat(after.get("remarks").asText())
                .as("remarks is @AuditRedact on both the entity and the response")
                .isEqualTo(REDACTED);
        assertThat(after.toString()).doesNotContain(STATE_REMARK_THAT_MUST_STAY_REDACTED);
    }

    @Test
    void an_aircraft_counter_record_carries_every_counter_reading() {
        String aircraftId = registerAircraft();
        preCleanAuditRowsThatOutliveTestRollback(jdbc, TENANT);

        Instant atDateTime = Instant.parse("2026-03-02T09:30:00Z");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("atDateTime", atDateTime.toString());
        payload.put("totalTowedGliderStarts", 41);
        payload.put("totalWinchLaunchStarts", 12);
        payload.put("totalSelfStarts", 3);
        payload.put("flightOperatingCounterInSeconds", 987_654L);
        ResponseEntity<String> res =
                post("/api/v1/aircraft/" + aircraftId + "/counters", payload);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map<String, Object> row = singleRowForAction("UPDATE");
        assertThat(row.get("target_entity_type")).isEqualTo("Aircraft");

        JsonNode after = parseSnapshot(row.get("after_state"));
        assertThat(after.get("atDateTime").asText()).isEqualTo(atDateTime.toString());
        assertThat(after.get("totalTowedGliderStarts").asInt()).isEqualTo(41);
        assertThat(after.get("totalWinchLaunchStarts").asInt()).isEqualTo(12);
        assertThat(after.get("totalSelfStarts").asInt()).isEqualTo(3);
        assertThat(after.get("flightOperatingCounterInSeconds").asLong()).isEqualTo(987_654L);
        assertNoFieldRendersTheRedactedSentinel(after);
    }

    private static void assertNoFieldRendersTheRedactedSentinel(JsonNode snapshot) {
        snapshot.fieldNames().forEachRemaining(field ->
                assertThat(snapshot.get(field).asText())
                        .as("%s must carry its value, not the redaction sentinel, in %s",
                                field, snapshot)
                        .isNotEqualTo(REDACTED));
    }

    private static void assertCarriesNoPersonIdentity(JsonNode snapshot) {
        assertThat(snapshot.toString())
                .as("a PersonClub snapshot must not widen PII exposure")
                .doesNotContain(PRIVATE_EMAIL_THAT_MUST_NEVER_REACH_THE_AUDIT_TRAIL)
                .doesNotContain("Lovelace")
                .doesNotContain("1815-12-10");
        for (String personIdentityField : List.of("firstname", "lastname", "emailPrivate",
                "emailBusiness", "birthday", "privatePhone", "mobilePhone", "addressLine1",
                "memberships", "personClubs")) {
            assertThat(snapshot.has(personIdentityField))
                    .as("%s belongs to Person, never to a PersonClub snapshot", personIdentityField)
                    .isFalse();
        }
    }

    private Map<String, Object> singleRowForAction(String action) {
        List<Map<String, Object>> rows = AuditTestSupport.findByTenant(jdbc, TENANT).stream()
                .filter(row -> action.equals(row.get("action")))
                .toList();
        assertThat(rows)
                .as("expected exactly one %s row in tenant %s", action, TENANT)
                .hasSize(1);
        return rows.get(0);
    }

    private String createPersonWithoutMembership() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("firstname", "Ada");
        body.put("lastname", "Lovelace");
        body.put("emailPrivate", PRIVATE_EMAIL_THAT_MUST_NEVER_REACH_THE_AUDIT_TRAIL);
        body.put("birthday", "1815-12-10");
        body.put("privatePhone", "+41 44 000 00 00");
        body.put("preferMailToBusinessMail", false);
        body.put("receiveOwnedAircraftStatisticReports", false);
        body.put("enableAddress", false);
        ResponseEntity<String> res = post("/api/v1/persons", body);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return readJson(res).get("id").asText();
    }

    private String registerAircraft() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("aircraftTypeId", SEED_AIRCRAFT_TYPE_GLIDER);
        body.put("immatriculation", "HB-Z" + shortMemberNumberSuffix());
        body.put("isTowingOrWinchRequired", true);
        body.put("isTowingStartAllowed", true);
        body.put("isWinchStartAllowed", true);
        body.put("isTowingAircraft", false);
        ResponseEntity<String> res = post("/api/v1/aircraft", body);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return readJson(res).get("id").asText();
    }

    private static Map<String, Object> membershipPayload(String memberNumber,
                                                         boolean gliderPilot,
                                                         boolean towPilot) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("memberNumber", memberNumber);
        body.put("isMotorPilot", false);
        body.put("isTowPilot", towPilot);
        body.put("isGliderInstructor", false);
        body.put("isGliderPilot", gliderPilot);
        body.put("isGliderTrainee", false);
        body.put("isPassenger", false);
        body.put("isWinchOperator", false);
        body.put("isMotorInstructor", false);
        body.put("receiveFlightReports", true);
        body.put("receiveAircraftReservationNotifications", false);
        body.put("receivePlanningDayRoleReminder", false);
        body.put("isActive", true);
        return body;
    }

    private static String shortMemberNumberSuffix() {
        String s = suffix();
        return s.length() > 5 ? s.substring(s.length() - 5) : s;
    }

    private ResponseEntity<String> post(String path, Map<String, Object> body) {
        return rest.exchange(authed(RequestEntity.post(URI.create(path))
                .contentType(MediaType.APPLICATION_JSON)).body(writeJson(body)), String.class);
    }

    private ResponseEntity<String> put(String path, Map<String, Object> body) {
        return rest.exchange(authed(RequestEntity.put(URI.create(path))
                .contentType(MediaType.APPLICATION_JSON)).body(writeJson(body)), String.class);
    }

    private RequestEntity.BodyBuilder authed(RequestEntity.BodyBuilder builder) {
        builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + clubAdminToken);
        return builder;
    }

    private RequestEntity.HeadersBuilder<?> authed(RequestEntity.HeadersBuilder<?> builder) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + clubAdminToken);
    }

    private static String writeJson(Map<String, Object> body) {
        try {
            return MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            throw new AssertionError("cannot serialise the request body", e);
        }
    }

    private static JsonNode readJson(ResponseEntity<String> res) {
        try {
            return MAPPER.readTree(res.getBody());
        } catch (Exception e) {
            throw new AssertionError("response body is not JSON: " + res.getBody(), e);
        }
    }
}
