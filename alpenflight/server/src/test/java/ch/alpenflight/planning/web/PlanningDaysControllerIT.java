package ch.alpenflight.planning.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
class PlanningDaysControllerIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final UUID DEV_SEED_CLUB =
            UUID.fromString("019e30c3-2c00-7001-8000-000000000001");
    private static final UUID OTHER_CLUB =
            UUID.fromString("019e30c6-2c00-7001-8000-0000000000f1");

    private static final String DEV_SEED_ID_BAND = "019e30c3-%";

    private static final LocalDate DAY = LocalDate.now(ZoneOffset.UTC).plusDays(3);

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;

    private String adminToken;
    private UUID locationId;
    private UUID instructorId;
    private UUID towingPilotId;
    private UUID flightOperatorId;
    private final List<UUID> mintedSubs = new java.util.ArrayList<>();

    @BeforeEach
    void seedAndAuth() {
        adminToken = jwts.mint(c -> c
                .claim("clubId", DEV_SEED_CLUB.toString())
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR"))));

        deleteOwnRowsLeavingTheDevSeedBandIntact();

        locationId = seedLocation(DEV_SEED_CLUB);
        seedAssignmentType(DEV_SEED_CLUB, "fluglehrer");
        seedAssignmentType(DEV_SEED_CLUB, "schlepppilot");
        seedAssignmentType(DEV_SEED_CLUB, "segelflugleiter");
        instructorId = seedPerson("Instr");
        towingPilotId = seedPerson("Tow");
        flightOperatorId = seedPerson("Ops");
    }

    @org.junit.jupiter.api.AfterEach
    void cleanupUsers() {
        for (UUID sub : mintedSubs) {
            jdbc.update("DELETE FROM t_mutation_audit_event WHERE actor_keycloak_sub = ?", sub.toString());
            jdbc.update("DELETE FROM t_user WHERE keycloak_sub = ?::uuid", sub.toString());
        }
        mintedSubs.clear();
    }

    @Test
    void create_returns201_andGetRoundTripsTheThreeCrewPersonIds() {
        ResponseEntity<String> res = post("/api/v1/planning-days", fullCrewPayload(DAY));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        JsonNode created = readJson(res);
        String id = created.get("id").asText();
        URI loc = res.getHeaders().getLocation();
        assertThat(loc).isNotNull();
        assertThat(loc.getPath()).isEqualTo("/api/v1/planning-days/" + id);

        ResponseEntity<String> got = get("/api/v1/planning-days/" + id, adminToken);
        assertThat(got.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode detail = readJson(got);
        assertThat(detail.get("instructorPersonId").asText()).isEqualTo("pn-" + instructorId);
        assertThat(detail.get("towingPilotPersonId").asText()).isEqualTo("pn-" + towingPilotId);
        assertThat(detail.get("flightOperatorPersonId").asText()).isEqualTo("pn-" + flightOperatorId);
        assertThat(detail.get("planningDate").asText()).isEqualTo(DAY.toString());
        assertThat(detail.get("locationId").asText()).isEqualTo("loc-" + locationId);
        assertThat(detail.get("numberOfAircraftReservations").asLong()).isZero();
        assertThat(detail.get("canUpdateRecord").asBoolean()).isTrue();
        assertThat(detail.get("canDeleteRecord").asBoolean()).isTrue();
    }

    @Test
    void create_duplicateClubDateLocation_returns409() {
        ResponseEntity<String> first = post("/api/v1/planning-days", fullCrewPayload(DAY));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> dup = post("/api/v1/planning-days", fullCrewPayload(DAY));
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(readJson(dup).get("key").asText()).isEqualTo("planning.day.duplicate");
    }

    @Test
    void delete_asNonAdminNonCreator_returns403_butTheCreatorThemselvesMayDelete() {
        UUID creatorSub = freshSub();
        String creatorToken = pilotToken(creatorSub);
        ResponseEntity<String> created = post("/api/v1/planning-days", fullCrewPayload(DAY), creatorToken);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = readJson(created).get("id").asText();

        UUID otherSub = freshSub();
        String otherToken = pilotToken(otherSub);
        ResponseEntity<String> denied = delete("/api/v1/planning-days/" + id, otherToken);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> ok = delete("/api/v1/planning-days/" + id, creatorToken);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void delete_excludesTheDaysAssignmentsFromEveryRead() {
        ResponseEntity<String> created = post("/api/v1/planning-days", fullCrewPayload(DAY));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID id = UUID.fromString(readJson(created).get("id").asText());
        assertThat(countAssignmentRowsBypassingEveryReadFilter(id))
                .as("a day with all 3 crew slots owns 3 child t_planning_day_assignment rows")
                .isEqualTo(3L);

        ResponseEntity<String> deleted = delete("/api/v1/planning-days/" + id, adminToken);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> got = get("/api/v1/planning-days/" + id, adminToken);
        assertThat(got.getStatusCode())
                .as("the deleted day is unreadable, so its crew slots are never returned")
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(listFutureDayIds(adminToken))
                .as("no leaked crew row decorates any surviving day in the future overview")
                .doesNotContain(id.toString());

        ResponseEntity<String> recreated = post("/api/v1/planning-days", fullCrewPayload(DAY));
        assertThat(recreated.getStatusCode())
                .as("the soft-delete freed the (club, date, location) slot on the partial unique index")
                .isEqualTo(HttpStatus.CREATED);
        UUID newId = UUID.fromString(readJson(recreated).get("id").asText());
        assertThat(newId).isNotEqualTo(id);
        assertThat(countAssignmentRowsBypassingEveryReadFilter(newId))
                .as("the re-created day owns a FRESH crew set, never inherited from the dead day")
                .isEqualTo(3L);
        assertThat(countAssignmentRowsBypassingEveryReadFilter(id))
                .as("soft-delete leaves the child rows physically present — the V4 ON DELETE CASCADE "
                        + "never fires — so only the parent's deleted_on IS NULL filter hides them")
                .isEqualTo(3L);

        ResponseEntity<String> liveGot = get("/api/v1/planning-days/" + newId, adminToken);
        assertThat(liveGot.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode liveDetail = readJson(liveGot);
        assertThat(liveDetail.get("instructorPersonId").asText()).isEqualTo("pn-" + instructorId);
        assertThat(liveDetail.get("towingPilotPersonId").asText()).isEqualTo("pn-" + towingPilotId);
        assertThat(liveDetail.get("flightOperatorPersonId").asText())
                .as("the live day exposes only its OWN crew, not the orphaned dead rows")
                .isEqualTo("pn-" + flightOperatorId);
    }

    @Test
    void ruleExpand_weekendOverTwoWeeks_createsExpectedDays_andReRunSkipsExisting() {
        LocalDate firstSaturdayClearOfTheSingleCreateTestsDay =
                nextOrSame(DAY.plusDays(7), java.time.DayOfWeek.SATURDAY);
        LocalDate lastDayOfTheInclusiveTwoWeekWindow =
                firstSaturdayClearOfTheSingleCreateTestsDay.plusWeeks(2).minusDays(1);
        int twoSaturdaysAndTwoSundays = 4;

        ResponseEntity<String> res = post("/api/v1/planning-days/create/rule",
                weekendRule(firstSaturdayClearOfTheSingleCreateTestsDay, lastDayOfTheInclusiveTwoWeekWindow));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode created = readJson(res);
        assertThat(created.isArray()).isTrue();
        assertThat(created).hasSize(twoSaturdaysAndTwoSundays);
        for (JsonNode day : created) {
            assertThat(day.get("locationId").asText()).isEqualTo("loc-" + locationId);
            assertThat(day.path("instructorPersonId").isNull() || day.path("instructorPersonId").isMissingNode())
                    .isTrue();
            assertThat(day.path("towingPilotPersonId").isNull() || day.path("towingPilotPersonId").isMissingNode())
                    .isTrue();
            assertThat(day.path("flightOperatorPersonId").isNull() || day.path("flightOperatorPersonId").isMissingNode())
                    .isTrue();
        }
        assertThat(countOwnPlanningDaysExcludingTheDevSeedBand()).isEqualTo(twoSaturdaysAndTwoSundays);

        ResponseEntity<String> rerun = post("/api/v1/planning-days/create/rule",
                weekendRule(firstSaturdayClearOfTheSingleCreateTestsDay, lastDayOfTheInclusiveTwoWeekWindow));
        assertThat(rerun.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(readJson(rerun)).hasSize(0);
        assertThat(countOwnPlanningDaysExcludingTheDevSeedBand()).isEqualTo(twoSaturdaysAndTwoSundays);
    }

    @Test
    void ruleExpand_noWeekdayFlags_createsNothing() {
        LocalDate start = LocalDate.now(ZoneOffset.UTC).plusDays(10);
        Map<String, Object> rule = ruleWithAllSevenWeekdayFlagsPresentAndFalse(start, start.plusDays(14));

        ResponseEntity<String> res = post("/api/v1/planning-days/create/rule", rule);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(readJson(res)).hasSize(0);
        assertThat(countOwnPlanningDaysExcludingTheDevSeedBand()).isZero();
    }

    @Test
    void ruleExpand_rangeOverCap_returns422() {
        LocalDate start = LocalDate.now(ZoneOffset.UTC).plusDays(1);
        Map<String, Object> rule = weekendRule(start, start.plusYears(5));

        ResponseEntity<String> res = post("/api/v1/planning-days/create/rule", rule);
        assertThat(res.getStatusCode().value()).isEqualTo(422);
        assertThat(readJson(res).get("key").asText()).isEqualTo("planning.rule.range");
        assertThat(countOwnPlanningDaysExcludingTheDevSeedBand()).isZero();
    }

    @Test
    void validate_noDuplicate_returnsValid() {
        ResponseEntity<String> res = post("/api/v1/planning-days/validate", validatePayload(DAY, null));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readJson(res).get("valid").asBoolean()).isTrue();
    }

    @Test
    void validate_existingClubDateLocation_returnsInvalidWithField_andPersistsNothing() {
        ResponseEntity<String> created = post("/api/v1/planning-days", fullCrewPayload(DAY));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> res = post("/api/v1/planning-days/validate", validatePayload(DAY, null));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode result = readJson(res);
        assertThat(result.get("valid").asBoolean()).isFalse();
        assertThat(result.get("field").asText()).isEqualTo("planningDate");
        assertThat(result.get("message").asText()).isNotBlank();
        assertThat(countOwnPlanningDaysExcludingTheDevSeedBand()).isEqualTo(1);
    }

    @Test
    void validate_editingOwnDay_isSelfExcluded_returnsValid() {
        ResponseEntity<String> created = post("/api/v1/planning-days", fullCrewPayload(DAY));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String ownId = readJson(created).get("id").asText();

        ResponseEntity<String> res = post("/api/v1/planning-days/validate",
                validatePayload(DAY, ownId));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readJson(res).get("valid").asBoolean()).isTrue();
    }

    @Test
    void validate_otherClubsDay_doesNotConflict_tenantScoped() {
        UUID otherDayId = provisionOtherClubThenSeedItsDayAtTheSameDateAndLocation();

        ResponseEntity<String> res = post("/api/v1/planning-days/validate", validatePayload(DAY, null));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readJson(res).get("valid").asBoolean())
                .as("the other club's day is invisible to the caller's tenant-scoped uniqueness probe")
                .isTrue();

        jdbc.update("DELETE FROM t_planning_day WHERE id = ?::uuid", otherDayId.toString());
    }

    @Test
    void get_crossTenant_returns404() {
        ResponseEntity<String> created = post("/api/v1/planning-days", fullCrewPayload(DAY));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = readJson(created).get("id").asText();

        String otherClubToken = jwts.mint(c -> c
                .claim("clubId", OTHER_CLUB.toString())
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR"))));
        ResponseEntity<String> got = get("/api/v1/planning-days/" + id, otherClubToken);
        assertThat(got.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }


    private void deleteOwnRowsLeavingTheDevSeedBandIntact() {
        jdbc.update("DELETE FROM t_planning_day WHERE operating_club_id = ?::uuid "
                + "AND id::text NOT LIKE '" + DEV_SEED_ID_BAND + "'", DEV_SEED_CLUB.toString());
        jdbc.update("DELETE FROM t_planning_day_assignment_type WHERE operating_club_id = ?::uuid "
                + "AND assignment_type_name IN ('fluglehrer','schlepppilot','segelflugleiter') "
                + "AND id::text NOT LIKE '" + DEV_SEED_ID_BAND + "'",
                DEV_SEED_CLUB.toString());
        jdbc.update("DELETE FROM t_location WHERE club_id = ?::uuid AND location_name LIKE 'PLN-IT-%'",
                DEV_SEED_CLUB.toString());
    }

    private UUID provisionOtherClubThenSeedItsDayAtTheSameDateAndLocation() {
        jdbc.update("""
                INSERT INTO t_club (id, clubname, club_key, country_id, club_state_id,
                        slug, public_registration_enabled)
                SELECT ?::uuid, 'Foil Club', 'FOILP', country_id, club_state_id,
                        'foil-club-pln-f1', false
                FROM t_club WHERE id = ?::uuid
                ON CONFLICT (id) DO NOTHING
                """, OTHER_CLUB.toString(), DEV_SEED_CLUB.toString());
        UUID otherDayId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO t_planning_day (id, operating_club_id, planning_date, location_id)
                VALUES (?::uuid, ?::uuid, ?::date, ?::uuid)
                """,
                otherDayId.toString(), OTHER_CLUB.toString(), DAY.toString(), locationId.toString());
        return otherDayId;
    }

    private Map<String, Object> fullCrewPayload(LocalDate day) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("planningDate", day.toString());
        m.put("locationId", "loc-" + locationId);
        m.put("instructorPersonId", "pn-" + instructorId);
        m.put("towingPilotPersonId", "pn-" + towingPilotId);
        m.put("flightOperatorPersonId", "pn-" + flightOperatorId);
        m.put("info", "IT planning day");
        return m;
    }

    private Map<String, Object> validatePayload(LocalDate day, @org.jspecify.annotations.Nullable String excludeId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("planningDate", day.toString());
        m.put("locationId", "loc-" + locationId);
        if (excludeId != null) {
            m.put("excludePlanningDayId", excludeId);
        }
        return m;
    }

    private Map<String, Object> ruleWithAllSevenWeekdayFlagsPresentAndFalse(LocalDate start, LocalDate end) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("startDate", start.toString());
        m.put("endDate", end.toString());
        m.put("everyMonday", false);
        m.put("everyTuesday", false);
        m.put("everyWednesday", false);
        m.put("everyThursday", false);
        m.put("everyFriday", false);
        m.put("everySaturday", false);
        m.put("everySunday", false);
        m.put("locationId", "loc-" + locationId);
        m.put("info", "IT rule-expand");
        return m;
    }

    private Map<String, Object> weekendRule(LocalDate start, LocalDate end) {
        Map<String, Object> m = ruleWithAllSevenWeekdayFlagsPresentAndFalse(start, end);
        m.put("everySaturday", true);
        m.put("everySunday", true);
        return m;
    }

    private static LocalDate nextOrSame(LocalDate from, java.time.DayOfWeek weekday) {
        LocalDate d = from;
        while (d.getDayOfWeek() != weekday) {
            d = d.plusDays(1);
        }
        return d;
    }

    private long countOwnPlanningDaysExcludingTheDevSeedBand() {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM t_planning_day WHERE operating_club_id = ?::uuid "
                + "AND deleted_on IS NULL AND id::text NOT LIKE '" + DEV_SEED_ID_BAND + "'",
                Long.class, DEV_SEED_CLUB.toString());
        return n == null ? 0L : n;
    }

    private long countAssignmentRowsBypassingEveryReadFilter(UUID dayId) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM t_planning_day_assignment WHERE planning_day_id = ?::uuid",
                Long.class, dayId.toString());
        return n == null ? 0L : n;
    }

    private List<String> listFutureDayIds(String token) {
        ResponseEntity<String> res = get("/api/v1/planning-days/overview/future", token);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = readJson(res);
        List<String> ids = new java.util.ArrayList<>();
        body.forEach(day -> ids.add(day.get("id").asText()));
        return ids;
    }

    private String pilotToken(UUID sub) {
        mintedSubs.add(sub);
        return jwts.mintJitReady(sub, DEV_SEED_CLUB, c -> c
                .claim("locale", "en")
                .claim("realm_access", Map.of("roles", List.of("PILOT"))));
    }

    private UUID freshSub() {
        return UUID.randomUUID();
    }

    private UUID seedLocation(UUID clubId) {
        UUID id = UUID.randomUUID();
        UUID countryId = jdbc.queryForObject("SELECT id FROM t_country LIMIT 1", UUID.class);
        UUID locationTypeId = jdbc.queryForObject("SELECT id FROM t_location_type LIMIT 1", UUID.class);
        jdbc.update("""
                INSERT INTO t_location (id, club_id, location_name, country_id, location_type_id,
                        is_inbound_route_required, is_outbound_route_required, is_fast_entry_record)
                VALUES (?::uuid, ?::uuid, ?, ?::uuid, ?::uuid, false, false, false)
                """,
                id.toString(), clubId.toString(), "PLN-IT-" + nano(),
                countryId.toString(), locationTypeId.toString());
        return id;
    }

    private void seedAssignmentType(UUID clubId, String name) {
        jdbc.update("""
                INSERT INTO t_planning_day_assignment_type
                    (id, operating_club_id, assignment_type_name, required_nr_of_assignments)
                VALUES (?::uuid, ?::uuid, ?, 1)
                """,
                UUID.randomUUID().toString(), clubId.toString(), name);
    }

    private UUID seedPerson(String firstName) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO t_person (id, firstname, lastname) VALUES (?::uuid, ?, ?)",
                id.toString(), firstName, "PlnCrew");
        return id;
    }

    private static String nano() {
        return Long.toString(System.nanoTime(), 36);
    }

    private ResponseEntity<String> get(String path, String token) {
        return rest.exchange(authed(RequestEntity.get(URI.create(path)), token).build(), String.class);
    }

    private ResponseEntity<String> post(String path, Object body) {
        return post(path, body, adminToken);
    }

    private ResponseEntity<String> post(String path, Object body, String token) {
        return rest.exchange(
                authed(RequestEntity.post(URI.create(path)).contentType(MediaType.APPLICATION_JSON), token)
                        .body(body),
                String.class);
    }

    private ResponseEntity<String> delete(String path, String token) {
        return rest.exchange(authed(RequestEntity.delete(URI.create(path)), token).build(), String.class);
    }

    private <T extends RequestEntity.BodyBuilder> T authed(T builder, String token) {
        builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return builder;
    }

    private RequestEntity.HeadersBuilder<?> authed(RequestEntity.HeadersBuilder<?> builder, String token) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static JsonNode readJson(ResponseEntity<String> res) {
        try {
            return MAPPER.readTree(res.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse response: " + res.getBody(), e);
        }
    }
}
