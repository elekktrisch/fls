package ch.alpenflight.me.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class MeNotificationPrefsControllerIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID CLUB_UUID =
            UUID.fromString("019e30c3-2c00-7001-8000-000000000001");
    private static final UUID LANG_DE_UUID =
            UUID.fromString("019e2e15-2c00-77d0-8000-0000000007d0");
    private static final String PATH = "/api/v1/me/club-membership/notification-prefs";

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;

    @BeforeEach
    void cleanFixtures() {
        jdbc.update("DELETE FROM t_user WHERE username LIKE 'menotif-it-%'");
        jdbc.update("DELETE FROM t_person_club WHERE member_number LIKE 'NOTIF-%'");
        jdbc.update("DELETE FROM t_person WHERE company_name = 'MeNotifIT'");
        jdbc.update("DELETE FROM t_mutation_audit_event "
                + "WHERE target_entity_type = 'PersonClubNotificationPrefs'");
    }

    @Test
    void getPrefs_returnsCallersOwnPrefs() {
        UUID kcSub = UUID.randomUUID();
        UUID personId = seedPerson("Amelia", "Earhart");
        seedMembership(personId, "NOTIF-1", true, false, true);
        seedUser(kcSub, "menotif-it-get", personId);

        ResponseEntity<String> res = get(pilotToken(kcSub));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode body = parse(res.getBody());
        assertThat(body.get("receiveFlightReports").asBoolean()).isTrue();
        assertThat(body.get("receiveAircraftReservationNotifications").asBoolean()).isFalse();
        assertThat(body.get("receivePlanningDayRoleReminder").asBoolean()).isTrue();
    }

    @Test
    void patchPrefs_persistsAndReGetReflects_adminFieldsUntouched() {
        UUID kcSub = UUID.randomUUID();
        UUID personId = seedPerson("Bessie", "Coleman");
        UUID pcId = seedMembership(personId, "NOTIF-9", false, false, false);
        jdbc.update("UPDATE t_person_club SET is_glider_pilot = true, is_motor_pilot = true, "
                        + "is_active = true WHERE id = ?::uuid",
                pcId.toString());
        seedUser(kcSub, "menotif-it-patch", personId);
        String token = pilotToken(kcSub);

        ResponseEntity<String> res = patch(Map.of(
                "receiveFlightReports", true,
                "receiveAircraftReservationNotifications", true,
                "receivePlanningDayRoleReminder", false), token);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT receive_flight_reports, receive_aircraft_reservation_notifications, "
                        + "receive_planning_day_role_reminder, member_number, is_glider_pilot, "
                        + "is_motor_pilot, is_active FROM t_person_club WHERE id = ?::uuid",
                pcId.toString());
        assertThat(row.get("receive_flight_reports")).isEqualTo(true);
        assertThat(row.get("receive_aircraft_reservation_notifications")).isEqualTo(true);
        assertThat(row.get("receive_planning_day_role_reminder")).isEqualTo(false);
        assertThat(row.get("member_number")).isEqualTo("NOTIF-9");
        assertThat(row.get("is_glider_pilot")).isEqualTo(true);
        assertThat(row.get("is_motor_pilot")).isEqualTo(true);
        assertThat(row.get("is_active")).isEqualTo(true);

        JsonNode body = parse(get(token).getBody());
        assertThat(body.get("receiveFlightReports").asBoolean()).isTrue();
        assertThat(body.get("receiveAircraftReservationNotifications").asBoolean()).isTrue();
        assertThat(body.get("receivePlanningDayRoleReminder").asBoolean()).isFalse();
    }

    @Test
    void patchPrefs_emitsReadableBeforeAfterAuditDiff() {
        UUID kcSub = UUID.randomUUID();
        UUID personId = seedPerson("Harriet", "Quimby");
        UUID pcId = seedMembership(personId, "NOTIF-7", false, false, false);
        seedUser(kcSub, "menotif-it-audit", personId);

        ResponseEntity<String> res = patch(Map.of(
                "receiveFlightReports", true,
                "receivePlanningDayRoleReminder", true), pilotToken(kcSub));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> auditRow = jdbc.queryForMap(
                "SELECT action, target_entity_type, target_entity_id, "
                        + "before_state::text AS before_state, after_state::text AS after_state "
                        + "FROM t_mutation_audit_event "
                        + "WHERE target_entity_type = 'PersonClubNotificationPrefs' "
                        + "AND target_entity_id = ?::uuid "
                        + "ORDER BY occurred_at DESC LIMIT 1",
                pcId.toString());
        assertThat(auditRow.get("action")).isEqualTo("UPDATE");

        JsonNode before = parse((String) auditRow.get("before_state"));
        JsonNode after = parse((String) auditRow.get("after_state"));
        assertThat(before.get("receiveFlightReports").asBoolean()).isFalse();
        assertThat(after.get("receiveFlightReports").asBoolean()).isTrue();
        assertThat(after.get("receivePlanningDayRoleReminder").asBoolean()).isTrue();
    }

    @Test
    void patchPrefs_resolvesCallerFromJwt_neverTouchesAnotherPrincipalsPrefs() {
        UUID subA = UUID.randomUUID();
        UUID subB = UUID.randomUUID();
        UUID personA = seedPerson("Ada", "Lovelace");
        UUID personB = seedPerson("Grace", "Hopper");
        UUID pcA = seedMembership(personA, "NOTIF-A", false, false, false);
        UUID pcB = seedMembership(personB, "NOTIF-B", false, false, false);
        seedUser(subA, "menotif-it-self", personA);
        seedUser(subB, "menotif-it-other", personB);

        ResponseEntity<String> res = patch(Map.of(
                "receiveFlightReports", true,
                "receiveAircraftReservationNotifications", true,
                "receivePlanningDayRoleReminder", true), pilotToken(subA));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(jdbc.queryForObject(
                "SELECT receive_flight_reports FROM t_person_club WHERE id = ?::uuid",
                Boolean.class, pcA.toString()))
                .isTrue();

        Map<String, Object> rowB = jdbc.queryForMap(
                "SELECT receive_flight_reports, receive_aircraft_reservation_notifications, "
                        + "receive_planning_day_role_reminder FROM t_person_club WHERE id = ?::uuid",
                pcB.toString());
        assertThat(rowB.get("receive_flight_reports")).isEqualTo(false);
        assertThat(rowB.get("receive_aircraft_reservation_notifications")).isEqualTo(false);
        assertThat(rowB.get("receive_planning_day_role_reminder")).isEqualTo(false);
    }

    @Test
    void getPrefs_callerWithNoLinkedPerson_returns409() {
        UUID kcSub = UUID.randomUUID();
        seedUser(kcSub, "menotif-it-noperson", null);

        ResponseEntity<String> res = get(pilotToken(kcSub));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void getPrefs_callerWithPersonButNoMembership_returns409() {
        UUID kcSub = UUID.randomUUID();
        UUID personId = seedPerson("Lone", "Wolf");
        seedUser(kcSub, "menotif-it-nomembership", personId);

        ResponseEntity<String> res = get(pilotToken(kcSub));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    private UUID seedPerson(String firstname, String lastname) {
        UUID personId = UUID.randomUUID();
        jdbc.update("INSERT INTO t_person (id, firstname, lastname, company_name) "
                        + "VALUES (?::uuid, ?, ?, 'MeNotifIT')",
                personId.toString(), firstname, lastname);
        return personId;
    }

    private UUID seedMembership(UUID personId, String memberNumber,
                                boolean flightReports, boolean reservations, boolean planningReminder) {
        UUID pcId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO t_person_club (id, person_id, club_id, member_number,
                        receive_flight_reports, receive_aircraft_reservation_notifications,
                        receive_planning_day_role_reminder, is_active)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?, false)
                """,
                pcId.toString(), personId.toString(), CLUB_UUID.toString(), memberNumber,
                flightReports, reservations, planningReminder);
        return pcId;
    }

    private UUID seedUser(UUID kcSub, String username, UUID personId) {
        UUID userId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO t_user (id, club_id, username, friendly_name, person_id,
                                    notification_email, phone_number, remarks, language_id,
                                    keycloak_sub)
                VALUES (?::uuid, ?::uuid, ?, ?, ?::uuid, ?, NULL, NULL, ?::uuid, ?::uuid)
                """,
                userId.toString(), CLUB_UUID.toString(), username, "Friendly " + username,
                personId == null ? null : personId.toString(),
                username + "@example.com", LANG_DE_UUID.toString(), kcSub.toString());
        return userId;
    }

    private String pilotToken(UUID kcSub) {
        return jwts.mint(c -> c
                .subject(kcSub.toString())
                .claim("clubId", CLUB_UUID.toString())
                .claim("realm_access", Map.of("roles", List.of("PILOT"))));
    }

    private ResponseEntity<String> get(String token) {
        return rest.exchange(
                RequestEntity.method(HttpMethod.GET, URI.create(PATH))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build(),
                String.class);
    }

    private ResponseEntity<String> patch(Object body, String token) {
        return rest.exchange(
                RequestEntity.method(HttpMethod.PATCH, URI.create(PATH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .body(body),
                String.class);
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse JSON: " + json, e);
        }
    }
}
