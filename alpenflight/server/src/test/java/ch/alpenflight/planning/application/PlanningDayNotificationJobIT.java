package ch.alpenflight.planning.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.planning.application.PlanningDayNotificationJob.RunSummary;
import ch.alpenflight.platform.mail.CapturedMailSender;
import ch.alpenflight.platform.mail.MailMessage;
import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.platform.tenancy.Tenants;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration proof of the planning-day notification job + the guarded run-now
 * affordance (J-6 T-10c, S-086 {@code [happy/email]} AC). Asserts against the
 * {@code @Primary} captured-outbox {@link CapturedMailSender} (T-10a) — no live
 * SMTP / mailpit needed.
 *
 * <ul>
 *   <li>The two exact-date passes for an opted-in club: a day+1 <em>with</em> a
 *       reservation → {@code planningday-ok} to the club address; a day+1
 *       <em>without</em> a reservation (and the club's reservation-less flag
 *       false) → {@code planningday-cancel} to the club address; a day+7 with an
 *       assigned person (email set) → {@code planningday-assignment-notification}
 *       to that person.</li>
 *   <li>The {@code POST .../notifications/run} authz: a {@code CLUB_ADMINISTRATOR}
 *       triggers it (200); a non-admin (PILOT) is forbidden (403); the run is
 *       tenant-scoped (the caller's own club only).</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({JwtTestFixture.class, CapturedMailSender.Config.class})
class PlanningDayNotificationJobIT extends PostgresIntegrationTest {

    private static final UUID CLUB_A = UUID.fromString("019e30c8-2c00-7001-8000-0000000000a1");
    private static final UUID CLUB_B = UUID.fromString("019e30c8-2c00-7001-8000-0000000000a2");
    private static final String CLUB_MAIL = "betrieb@club-a.example";

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);
    private static final LocalDate DAY_PLUS_1 = TODAY.plusDays(1);
    private static final LocalDate DAY_PLUS_7 = TODAY.plusDays(7);

    @Autowired private PlanningDayNotificationJob job;
    @Autowired private CapturedMailSender outbox;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtTestFixture jwts;
    @Autowired private TestRestTemplate rest;

    private UUID locationA;
    private UUID assignedPersonId;
    private UUID instructorTypeId;

    @BeforeEach
    void seed() {
        outbox.clear();
        // Own-rows pre-clean (RESTRICT FKs); scope to these IT clubs.
        for (UUID club : List.of(CLUB_A, CLUB_B)) {
            jdbc.update("DELETE FROM t_planning_day WHERE operating_club_id = ?::uuid", club.toString());
            jdbc.update("DELETE FROM t_aircraft_reservation WHERE operating_club_id = ?::uuid",
                    club.toString());
            jdbc.update("DELETE FROM t_planning_day_assignment_type WHERE operating_club_id = ?::uuid",
                    club.toString());
            jdbc.update("DELETE FROM t_aircraft WHERE managing_club_id = ?::uuid", club.toString());
            jdbc.update("DELETE FROM t_location WHERE club_id = ?::uuid", club.toString());
            jdbc.update("DELETE FROM t_club WHERE id = ?::uuid", club.toString());
        }

        insertClub(CLUB_A, "njalpha", CLUB_MAIL, false);
        insertClub(CLUB_B, "njbravo", null, false);
        locationA = seedLocation(CLUB_A, "Bern-Belp");
        instructorTypeId = seedAssignmentType(CLUB_A, "fluglehrer");
        // A person with a private email → the week-ahead assignee recipient.
        assignedPersonId = seedPerson("Anna", "Assignee", "anna@pilot.example", null, false);
    }

    @Test
    void both_passes_send_ok_cancel_and_assignment_mails_to_the_right_recipients() {
        // day+1 WITH a reservation → planningday-ok to the club address.
        UUID dayWithReservation = seedPlanningDay(CLUB_A, DAY_PLUS_1, locationA);
        seedReservation(CLUB_A, locationA, DAY_PLUS_1);
        // A SECOND day+1 at a different location WITHOUT a reservation + club flag
        // false → planningday-cancel to the club address.
        UUID location2 = seedLocation(CLUB_A, "Thun");
        seedPlanningDay(CLUB_A, DAY_PLUS_1, location2);
        // day+7 with an assigned instructor (email set) → assignment notification.
        UUID weekAheadDay = seedPlanningDay(CLUB_A, DAY_PLUS_7, locationA);
        seedAssignment(weekAheadDay, CLUB_A, instructorTypeId, assignedPersonId);

        RunSummary summary = Tenants.runAs(CLUB_A, job::runForCurrentClub);

        assertThat(summary.imminentMailCount()).as("two day+1 days → two club mails").isEqualTo(2);
        assertThat(summary.weekAheadMailCount()).as("one assigned person on the day+7 day").isEqualTo(1);

        List<MailMessage> sent = outbox.sent();
        assertThat(sent).hasSize(3);

        MailMessage ok = single(sent, "Flugbetriebstag findet statt");
        assertThat(ok.to()).containsExactly(CLUB_MAIL);
        assertThat(ok.htmlBody()).contains("findet statt").contains("Bern-Belp");

        MailMessage cancel = single(sent, "Flugbetriebstag abgesagt");
        assertThat(cancel.to()).containsExactly(CLUB_MAIL);
        assertThat(cancel.htmlBody()).contains("abgesagt").contains("Thun");

        MailMessage assignment = single(sent, "Erinnerung: Einteilung Flugbetriebstag");
        assertThat(assignment.to()).containsExactly("anna@pilot.example");
        assertThat(assignment.htmlBody())
                .contains("Anna Assignee")
                .contains("fluglehrer")
                .contains("Bern-Belp");

        assertThat(dayWithReservation).isNotNull();
    }

    @Test
    void cancel_when_no_reservation_but_ok_when_club_allows_reservationless_days() {
        // Flip CLUB_A to allow reservation-less days → the day+1 with no
        // reservation now sends OK instead of cancel (the Club.shouldSendPlanningDayOk rule).
        jdbc.update("UPDATE t_club SET use_planning_day_without_reservations = true WHERE id = ?::uuid",
                CLUB_A.toString());
        seedPlanningDay(CLUB_A, DAY_PLUS_1, locationA);

        RunSummary summary = Tenants.runAs(CLUB_A, job::runForCurrentClub);

        assertThat(summary.imminentMailCount()).isEqualTo(1);
        List<MailMessage> sent = outbox.sent();
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).subject()).isEqualTo("Flugbetriebstag findet statt");
        assertThat(sent.get(0).to()).containsExactly(CLUB_MAIL);
    }

    @Test
    void run_now_endpoint_is_clubAdmin_gated_and_tenant_scoped() {
        seedPlanningDay(CLUB_A, DAY_PLUS_1, locationA);

        // Non-admin (PILOT) → 403.
        String pilotToken = jwts.mint(c -> c
                .claim("clubId", CLUB_A.toString())
                .claim("realm_access", Map.of("roles", List.of("PILOT"))));
        ResponseEntity<String> forbidden = runNow(pilotToken);
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(outbox.sent()).as("a forbidden call sent nothing").isEmpty();

        // ClubAdmin of CLUB_A → 200, mails for CLUB_A's day only (tenant-scoped).
        String adminToken = jwts.mint(c -> c
                .claim("clubId", CLUB_A.toString())
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR"))));
        ResponseEntity<String> ok = runNow(adminToken);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(outbox.sent())
                .as("one cancel mail for CLUB_A's reservation-less day+1, to the club address")
                .hasSize(1);
        assertThat(outbox.sent().get(0).to()).containsExactly(CLUB_MAIL);
    }

    // ----- helpers -----

    private ResponseEntity<String> runNow(String token) {
        return rest.exchange(
                RequestEntity.post(URI.create("/api/v1/planning-days/notifications/run"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build(),
                String.class);
    }

    private static MailMessage single(List<MailMessage> sent, String subject) {
        return sent.stream()
                .filter(m -> m.subject().equals(subject))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no mail with subject: " + subject));
    }

    private void insertClub(UUID id, String slug, String mailTo, boolean reservationless) {
        UUID countryId = jdbc.queryForObject("SELECT id FROM t_country LIMIT 1", UUID.class);
        UUID clubStateId = jdbc.queryForObject("SELECT id FROM t_club_state LIMIT 1", UUID.class);
        jdbc.update("""
                INSERT INTO t_club (id, clubname, club_key, country_id, club_state_id, slug,
                        public_registration_enabled, send_planning_day_info_mail_to,
                        use_planning_day_without_reservations)
                VALUES (?::uuid, ?, ?, ?::uuid, ?::uuid, ?, false, ?, ?)
                """,
                id.toString(), "PLN-NJ-" + slug, "NJ_" + slug.charAt(2),
                countryId.toString(), clubStateId.toString(), "PLN-NJ-" + slug,
                mailTo, reservationless);
    }

    private UUID seedLocation(UUID clubId, String name) {
        UUID id = UUID.randomUUID();
        UUID countryId = jdbc.queryForObject("SELECT id FROM t_country LIMIT 1", UUID.class);
        UUID locationTypeId = jdbc.queryForObject("SELECT id FROM t_location_type LIMIT 1", UUID.class);
        jdbc.update("""
                INSERT INTO t_location (id, club_id, location_name, country_id, location_type_id,
                        is_inbound_route_required, is_outbound_route_required, is_fast_entry_record)
                VALUES (?::uuid, ?::uuid, ?, ?::uuid, ?::uuid, false, false, false)
                """,
                id.toString(), clubId.toString(), "PLN-NJ-" + name + "-" + nano(),
                countryId.toString(), locationTypeId.toString());
        return id;
    }

    private UUID seedAssignmentType(UUID clubId, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO t_planning_day_assignment_type
                    (id, operating_club_id, assignment_type_name, required_nr_of_assignments)
                VALUES (?::uuid, ?::uuid, ?, 1)
                """,
                id.toString(), clubId.toString(), name);
        return id;
    }

    private UUID seedPerson(String first, String last, String emailPrivate,
                            String emailBusiness, boolean preferBusiness) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO t_person (id, firstname, lastname, email_private, email_business,
                        prefer_mail_to_business_mail)
                VALUES (?::uuid, ?, ?, ?, ?, ?)
                """,
                id.toString(), first, last, emailPrivate, emailBusiness, preferBusiness);
        return id;
    }

    private UUID seedPlanningDay(UUID clubId, LocalDate date, UUID locationId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO t_planning_day (id, operating_club_id, planning_date, location_id, info)
                VALUES (?::uuid, ?::uuid, ?, ?::uuid, ?)
                """,
                id.toString(), clubId.toString(), java.sql.Date.valueOf(date),
                locationId.toString(), "NJ day");
        return id;
    }

    private void seedAssignment(UUID planningDayId, UUID clubId, UUID typeId, UUID personId) {
        jdbc.update("""
                INSERT INTO t_planning_day_assignment
                    (id, operating_club_id, planning_day_id, assigned_person_id, assignment_type_id)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid)
                """,
                UUID.randomUUID().toString(), clubId.toString(), planningDayId.toString(),
                personId.toString(), typeId.toString());
    }

    private void seedReservation(UUID clubId, UUID locationId, LocalDate day) {
        UUID aircraftId = seedAircraft(clubId);
        UUID pilot = seedPerson("Res", "Pilot", "res@pilot.example", null, false);
        java.time.Instant start = day.atStartOfDay(ZoneOffset.UTC).plusHours(9).toInstant();
        jdbc.update("""
                INSERT INTO t_aircraft_reservation
                    (id, operating_club_id, aircraft_id, pilot_person_id, location_id,
                     reservation_start, reservation_end, is_all_day)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, ?, false)
                """,
                UUID.randomUUID().toString(), clubId.toString(), aircraftId.toString(),
                pilot.toString(), locationId.toString(),
                java.sql.Timestamp.from(start),
                java.sql.Timestamp.from(start.plusSeconds(7200)));
    }

    private UUID seedAircraft(UUID managingClubId) {
        UUID id = UUID.randomUUID();
        UUID aircraftTypeId = jdbc.queryForObject("SELECT id FROM t_aircraft_type LIMIT 1", UUID.class);
        jdbc.update("""
                INSERT INTO t_aircraft (id, managing_club_id, owner_club_id, aircraft_type_id,
                        immatriculation, is_towing_or_winch_required, is_towing_start_allowed,
                        is_winch_start_allowed, is_towing_aircraft, is_fast_entry_record, nr_of_seats)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, false, false, false, false, false, 2)
                """,
                id.toString(), managingClubId.toString(), managingClubId.toString(),
                aircraftTypeId.toString(), uniqueImmat());
        return id;
    }

    private static String nano() {
        return Long.toString(System.nanoTime(), 36);
    }

    private static String uniqueImmat() {
        String suffix = Long.toString(System.nanoTime(), 36);
        return ("HB-N" + suffix).substring(0, Math.min(15, 4 + suffix.length()));
    }
}
