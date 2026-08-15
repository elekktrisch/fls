package ch.alpenflight.me.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TwoClubFixture;
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
class ClubDashboardControllerIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final UUID PROCESS_STATE_INVALID =
            UUID.fromString("019e2e15-2c00-7a99-8000-000000003a99");
    private static final UUID PROCESS_STATE_VALID =
            UUID.fromString("019e2e15-2c00-7a9a-8000-000000003a9a");
    private static final UUID AS_CREATED_NOT_PROCESSED = null;

    private static final LocalDate TODAY_UTC = LocalDate.now(ZoneOffset.UTC);
    private static final LocalDate THIRTY_DAYS_AGO = TODAY_UTC.minusDays(30);

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;
    @Autowired ClubRepository clubs;
    @Autowired CountryRepository countries;
    @Autowired ClubStateRepository clubStates;

    private UUID clubA;
    private UUID clubB;
    private String aircraftAExternal;

    @BeforeEach
    void seed() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, "cdash", "CDSH");
        fixture.seed();
        clubA = fixture.clubA();
        clubB = fixture.clubB();
        cleanFlightRows(clubA, clubB);
        aircraftAExternal = "ac-" + seedAircraft(clubA);
    }

    @Test
    void clubAdmin_counts_today_and_pending_scoped_to_own_club() {
        String adminA = adminToken(clubA);
        String aircraftB = "ac-" + seedAircraft(clubB);

        createFlight(adminA, aircraftAExternal, TODAY_UTC, AS_CREATED_NOT_PROCESSED);
        createFlight(adminA, aircraftAExternal, TODAY_UTC, AS_CREATED_NOT_PROCESSED);
        createFlight(adminA, aircraftAExternal, TODAY_UTC, PROCESS_STATE_VALID);
        createFlight(adminA, aircraftAExternal, THIRTY_DAYS_AGO, PROCESS_STATE_INVALID);
        createFlight(adminA, aircraftAExternal, THIRTY_DAYS_AGO, PROCESS_STATE_VALID);

        String adminB = adminToken(clubB);
        createFlight(adminB, aircraftB, TODAY_UTC, AS_CREATED_NOT_PROCESSED);
        createFlight(adminB, aircraftB, THIRTY_DAYS_AGO, PROCESS_STATE_INVALID);

        JsonNode body = get("/api/v1/me/club-dashboard", adminA);
        assertThat(body.get("todaysFlights").asLong())
                .as("today's flights = only club A's flight_date=today rows")
                .isEqualTo(3L);
        assertThat(body.get("pendingValidation").asLong())
                .as("pending = club A's NotProcessed (2 today) + Invalid (1 past); Valid excluded")
                .isEqualTo(3L);
    }

    @Test
    void counts_are_tenant_isolated_clubB_sees_only_its_own() {
        String adminA = adminToken(clubA);
        createFlight(adminA, aircraftAExternal, TODAY_UTC, AS_CREATED_NOT_PROCESSED);
        createFlight(adminA, aircraftAExternal, TODAY_UTC, AS_CREATED_NOT_PROCESSED);

        String aircraftB = "ac-" + seedAircraft(clubB);
        String adminB = adminToken(clubB);
        createFlight(adminB, aircraftB, TODAY_UTC, AS_CREATED_NOT_PROCESSED);

        JsonNode body = get("/api/v1/me/club-dashboard", adminB);
        assertThat(body.get("todaysFlights").asLong())
                .as("club B sees only its own one flight, not club A's two")
                .isEqualTo(1L);
        assertThat(body.get("pendingValidation").asLong()).isEqualTo(1L);
    }

    @Test
    void pilot_without_clubAdmin_role_is_rejected_403() {
        String pilot = jwts.mint(c -> c
                .claim("clubId", clubA.toString())
                .claim("realm_access", Map.of("roles", List.of("PILOT"))));
        ResponseEntity<String> res = rawGet("/api/v1/me/club-dashboard", pilot);
        assertThat(res.getStatusCode())
                .as("the club-admin dashboard is CLUB_ADMINISTRATOR-only")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    private String adminToken(UUID clubId) {
        return jwts.mint(c -> c
                .claim("clubId", clubId.toString())
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR"))));
    }

    private void createFlight(String token, String aircraftExternal, LocalDate date,
                              UUID processStateToForceViaJdbc) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("flightAircraftType", "GLIDER");
        body.put("aircraftId", aircraftExternal);
        body.put("flightDate", date.toString());
        body.put("isSoloFlight", false);
        body.put("noStartTimeInformation", false);
        body.put("noLdgTimeInformation", false);
        body.put("crew", List.of());
        ResponseEntity<String> res = post("/api/v1/flights", body, token);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        if (processStateToForceViaJdbc != null) {
            String idExternal = readJson(res).get("id").asText();
            UUID flightUuid = UUID.fromString(idExternal.substring("fl-".length()));
            jdbc.update("UPDATE t_flight SET process_state_id = ?::uuid WHERE id = ?::uuid",
                    processStateToForceViaJdbc.toString(), flightUuid.toString());
        }
    }

    private UUID seedAircraft(UUID managingClubId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO t_aircraft (id, managing_club_id, owner_club_id, aircraft_type_id,
                                      immatriculation, is_towing_or_winch_required,
                                      is_towing_start_allowed, is_winch_start_allowed,
                                      is_towing_aircraft, is_fast_entry_record, nr_of_seats)
                VALUES (?::uuid, ?::uuid, ?::uuid, '019e2e15-2c00-7af9-8000-000000002af9',
                        ?, false, false, false, false, false, 2)
                """,
                id.toString(), managingClubId.toString(), managingClubId.toString(),
                "HB-CD" + Integer.toHexString(id.hashCode() & 0xfff));
        return id;
    }

    private void cleanFlightRows(UUID... clubIds) {
        for (UUID clubId : clubIds) {
            jdbc.update("DELETE FROM t_flight WHERE operating_club_id = ?::uuid",
                    clubId.toString());
        }
    }

    private ResponseEntity<String> post(String path, Object body, String token) {
        return rest.exchange(
                RequestEntity.post(URI.create(path))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .body(body),
                String.class);
    }

    private JsonNode get(String path, String token) {
        return readJson(rawGet(path, token));
    }

    private ResponseEntity<String> rawGet(String path, String token) {
        return rest.exchange(
                RequestEntity.get(URI.create(path))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build(),
                String.class);
    }

    private static JsonNode readJson(ResponseEntity<String> res) {
        try {
            return MAPPER.readTree(res.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse response: " + res.getBody(), e);
        }
    }
}
