package ch.alpenflight.flights.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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

/**
 * Full-stack HTTP integration test for the J-7 T-05 flight-reports page endpoint
 * ({@code POST /api/v1/flightreports/page/{start}/{size}}). Proves the web-layer
 * wire contract: a happy page query returns items + summaries; tenant isolation
 * (a club-A caller filtering by a club-B location never sees club-B rows — the
 * J-7 tenancy-hole correction); and a PILOT-role caller can read (the J-3
 * PILOT-403 authz lesson). The grouping/formula parity is proven at the
 * service layer ({@code FlightReportQueryServiceIT}); this stays at the wire.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class FlightReportsControllerIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID CLUB_A = UUID.fromString("019e30c5-2c00-7001-8000-0000000000b1");
    private static final UUID CLUB_B = UUID.fromString("019e30c5-2c00-7001-8000-0000000000b2");

    private static final int TYPE_GLIDER = 1;
    /** {@code t_start_type} WINCH_LAUNCH id (V2 seed) → legacy AircraftStartType int 1. */
    private static final UUID WINCH_LAUNCH_START_TYPE =
            UUID.fromString("019e2e15-2c00-7fa0-8000-000000000fa0");

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;

    @BeforeEach
    void cleanAndSeedClubs() {
        // Shared Testcontainers DB — clear this IT's rows from prior runs first
        // (FK order: flight_crew → flight → location/flight_type/aircraft/person).
        jdbc.update("DELETE FROM t_flight_crew WHERE flight_id IN "
                + "(SELECT id FROM t_flight WHERE operating_club_id IN (?::uuid, ?::uuid))",
                CLUB_A.toString(), CLUB_B.toString());
        jdbc.update("DELETE FROM t_flight WHERE operating_club_id IN (?::uuid, ?::uuid)",
                CLUB_A.toString(), CLUB_B.toString());
        jdbc.update("DELETE FROM t_flight_type WHERE operating_club_id IN (?::uuid, ?::uuid)",
                CLUB_A.toString(), CLUB_B.toString());
        jdbc.update("DELETE FROM t_location WHERE club_id IN (?::uuid, ?::uuid)",
                CLUB_A.toString(), CLUB_B.toString());
        jdbc.update("DELETE FROM t_aircraft WHERE managing_club_id IN (?::uuid, ?::uuid)",
                CLUB_A.toString(), CLUB_B.toString());
        seedClub(CLUB_A, "rep-b1");
        seedClub(CLUB_B, "rep-b2");
    }

    @Test
    void pageQuery_clubAdmin_returnsItemsAndSummaries() {
        UUID aircraft = seedAircraft(CLUB_A);
        UUID location = seedLocation(CLUB_A, "Birrfeld");
        UUID flightType = seedFlightType(CLUB_A, "Schul", "SCH");
        UUID pilot = seedPerson("Tester", "Anna");

        Instant start = Instant.parse("2026-05-15T08:00:00Z");
        Instant ldg = Instant.parse("2026-05-15T09:30:00Z");
        UUID flight = seedFlight(CLUB_A, aircraft, LocalDate.of(2026, 5, 15), start, ldg,
                location, location, flightType);
        seedCrew(flight, pilot, FlightCrewTypeIds_PILOT);

        String token = mintToken(CLUB_A, "CLUB_ADMINISTRATOR");
        // Person-filtered → the summary person-branch populates.
        Map<String, Object> body = Map.of("searchFilter", Map.of(
                "flightDateFrom", "2026-05-01",
                "flightDateTo", "2026-05-31",
                "flightCrewPersonId", "pn-" + pilot));

        ResponseEntity<String> res = post("/api/v1/flightreports/page/0/100", body, token);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode page = readJson(res);
        assertThat(page.get("totalRows").asLong()).isEqualTo(1);
        JsonNode items = page.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("pilotName").asText()).isEqualTo("Tester Anna");
        assertThat(items.get(0).get("flightTypeName").asText()).isEqualTo("Schul");

        JsonNode summaries = page.get("summaries");
        assertThat(summaries.isArray()).isTrue();
        // Person branch: at least Pilot (Glider) + Total.
        assertThat(summaries.size()).isGreaterThanOrEqualTo(2);
        assertThat(summary(summaries, "Total").get("totalFlights").asInt()).isEqualTo(1);
    }

    @Test
    void pageQuery_clubAFilteringByClubBLocation_seesNoClubBRows() {
        UUID aircraftA = seedAircraft(CLUB_A);
        UUID aircraftB = seedAircraft(CLUB_B);
        UUID locationA = seedLocation(CLUB_A, "HomeA");
        UUID ftA = seedFlightType(CLUB_A, "TypeA", "TA");
        UUID ftB = seedFlightType(CLUB_B, "TypeB", "TB");

        Instant start = Instant.parse("2026-05-15T08:00:00Z");
        Instant ldg = Instant.parse("2026-05-15T09:00:00Z");
        // Club A flight at locationA.
        seedFlight(CLUB_A, aircraftA, LocalDate.of(2026, 5, 15), start, ldg,
                locationA, locationA, ftA);
        // Club B flight that ALSO references locationA (cross-club filter target).
        seedFlight(CLUB_B, aircraftB, LocalDate.of(2026, 5, 15), start, ldg,
                locationA, locationA, ftB);

        String token = mintToken(CLUB_A, "CLUB_ADMINISTRATOR");
        Map<String, Object> body = Map.of("searchFilter", Map.of(
                "locationId", "loc-" + locationA,
                "towFlights", true));

        ResponseEntity<String> res = post("/api/v1/flightreports/page/0/100", body, token);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode page = readJson(res);
        // Only club A's flight — the legacy tenancy hole would have leaked B's.
        assertThat(page.get("totalRows").asLong()).isEqualTo(1);
        assertThat(page.get("items")).hasSize(1);
    }

    @Test
    void pageQuery_pilotRole_canRead() {
        UUID aircraft = seedAircraft(CLUB_A);
        UUID location = seedLocation(CLUB_A, "Loc");
        UUID flightType = seedFlightType(CLUB_A, "T", "T");
        Instant start = Instant.parse("2026-05-15T08:00:00Z");
        Instant ldg = Instant.parse("2026-05-15T09:00:00Z");
        seedFlight(CLUB_A, aircraft, LocalDate.of(2026, 5, 15), start, ldg,
                location, location, flightType);

        // A low-privilege PILOT principal (the J-3 PILOT-403 lesson) must read.
        String token = mintToken(CLUB_A, "PILOT");
        ResponseEntity<String> res = post("/api/v1/flightreports/page/0/100",
                Map.of(), token);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readJson(res).get("totalRows").asLong()).isEqualTo(1);
    }

    @Test
    void exportExcel_streamsXlsxWithLegacyLayout() throws Exception {
        UUID aircraft = seedAircraft(CLUB_A);
        UUID location = seedLocation(CLUB_A, "Birrfeld");
        UUID flightType = seedFlightType(CLUB_A, "Schul", "SCH");
        UUID pilot = seedPerson("Tester", "Anna");

        Instant start = Instant.parse("2026-05-15T08:05:00Z");
        Instant ldg = Instant.parse("2026-05-15T09:35:00Z"); // 1h30m duration
        // WINCH_LAUNCH start type → legacy AircraftStartType int 1 (the StartType-int parity contract).
        UUID flight = seedFlightWithStartType(CLUB_A, aircraft, LocalDate.of(2026, 5, 15),
                start, ldg, location, location, flightType, WINCH_LAUNCH_START_TYPE);
        seedCrew(flight, pilot, FlightCrewTypeIds_PILOT);

        String token = mintToken(CLUB_A, "CLUB_ADMINISTRATOR");
        Map<String, Object> body = Map.of("searchFilter", Map.of(
                "flightDateFrom", "2026-05-01",
                "flightDateTo", "2026-05-31"));

        ResponseEntity<byte[]> res = rest.exchange(
                RequestEntity.post(URI.create("/api/v1/flightreports/export/excel/0/100"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body),
                byte[].class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE))
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(res.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment").contains("FlightReports.xlsx");

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(res.getBody()))) {
            Sheet sheet = wb.getSheet("Flights");
            assertThat(sheet).as("sheet named Flights").isNotNull();

            // Metadata: A1 = "Flights", A3 = "Excel Erstellt:", C3 timestamp format.
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Flights");
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("Excel Erstellt:");
            Cell c3 = sheet.getRow(2).getCell(2);
            assertThat(c3.getCellStyle().getDataFormatString()).isEqualTo("dd.mm.yyyy HH:MM:ss");

            // Header = row 5 (index 4); preserved typo + skipped col 17.
            Row header = sheet.getRow(4);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("Flight ID");
            assertThat(header.getCell(9).getStringCellValue()).isEqualTo("StartTime UTC");
            assertThat(header.getCell(10).getStringCellValue()).isEqualTo("LdgTime UCT"); // typo preserved
            assertThat(header.getCell(12).getStringCellValue()).isEqualTo("IsSoloFlight");
            assertThat(header.getCell(13).getStringCellValue()).isEqualTo("StartType");
            // Column 17 (index 16) intentionally blank.
            Cell skipped = header.getCell(16);
            assertThat(skipped == null || skipped.getStringCellValue().isEmpty())
                    .as("column 17 header is blank").isTrue();
            assertThat(header.getCell(17).getStringCellValue()).isEqualTo("FlightComment");

            // Data row = row 6 (index 5): key cells + number formats.
            Row data = sheet.getRow(5);
            assertThat(data.getCell(2).getStringCellValue()).startsWith("HB-"); // Immatriculation
            assertThat(data.getCell(3).getStringCellValue()).isEqualTo("Tester Anna"); // PilotName
            assertThat(data.getCell(9).getCellStyle().getDataFormatString()).isEqualTo("HH:MM"); // StartTime
            assertThat(data.getCell(10).getCellStyle().getDataFormatString()).isEqualTo("HH:MM"); // LdgTime
            assertThat(data.getCell(11).getCellStyle().getDataFormatString()).isEqualTo("[H]:MM"); // Duration
            assertThat((int) data.getCell(12).getNumericCellValue()).isEqualTo(0); // IsSoloFlight 0
            assertThat((int) data.getCell(13).getNumericCellValue()).isEqualTo(1); // StartType WINCH=1
        }
    }

    // ---------------------------------------------------------------- helpers

    /** {@code t_flight_crew_type} PilotOrStudent id (legacy fixed seed). */
    private static final UUID FlightCrewTypeIds_PILOT =
            ch.alpenflight.flights.domain.FlightCrewTypeIds.PILOT_OR_STUDENT;

    private static JsonNode summary(JsonNode summaries, String groupBy) {
        for (JsonNode s : summaries) {
            if (groupBy.equals(s.path("groupBy").asText())) {
                return s;
            }
        }
        throw new AssertionError("no summary row '" + groupBy + "'");
    }

    private String mintToken(UUID clubId, String role) {
        return jwts.mint(c -> c
                .claim("clubId", clubId.toString())
                .claim("realm_access", Map.of("roles", List.of(role))));
    }

    private void seedClub(UUID id, String slug) {
        UUID anyCountry = jdbc.queryForObject("SELECT country_id FROM t_club LIMIT 1", UUID.class);
        UUID anyState = jdbc.queryForObject("SELECT club_state_id FROM t_club LIMIT 1", UUID.class);
        jdbc.update("""
                INSERT INTO t_club (id, clubname, club_key, country_id, club_state_id,
                        slug, public_registration_enabled)
                VALUES (?::uuid, ?, ?, ?::uuid, ?::uuid, ?, false)
                ON CONFLICT (id) DO NOTHING
                """,
                id.toString(), "Report Club " + slug, slug.toUpperCase(Locale.ROOT),
                anyCountry.toString(), anyState.toString(), slug);
    }

    private UUID seedAircraft(UUID managingClubId) {
        UUID id = UUID.randomUUID();
        UUID acType = jdbc.queryForObject("SELECT id FROM t_aircraft_type LIMIT 1", UUID.class);
        jdbc.update("""
                INSERT INTO t_aircraft (id, managing_club_id, owner_club_id, aircraft_type_id,
                        immatriculation, is_towing_or_winch_required, is_towing_start_allowed,
                        is_winch_start_allowed, is_towing_aircraft, is_fast_entry_record, nr_of_seats)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, false, false, false, false, false, 2)
                """,
                id.toString(), managingClubId.toString(), managingClubId.toString(),
                acType.toString(), "HB-" + id.toString().substring(0, 6).toUpperCase(Locale.ROOT));
        return id;
    }

    private UUID seedLocation(UUID clubId, String name) {
        UUID id = UUID.randomUUID();
        UUID locType = jdbc.queryForObject("SELECT id FROM t_location_type LIMIT 1", UUID.class);
        UUID country = jdbc.queryForObject("SELECT id FROM t_country LIMIT 1", UUID.class);
        jdbc.update("""
                INSERT INTO t_location (id, club_id, location_name, location_type_id, country_id,
                        is_inbound_route_required, is_outbound_route_required, is_fast_entry_record)
                VALUES (?::uuid, ?::uuid, ?, ?::uuid, ?::uuid, false, false, false)
                """,
                id.toString(), clubId.toString(), name, locType.toString(), country.toString());
        return id;
    }

    private UUID seedFlightType(UUID clubId, String name, String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO t_flight_type (id, operating_club_id, flight_type_name, flight_code,
                        instructor_required, observer_pilot_or_instructor_required, is_check_flight,
                        is_passenger_flight, is_solo_flight, is_for_glider_flights,
                        is_for_tow_flights, is_for_motor_flights, is_flight_cost_balance_selectable,
                        is_coupon_number_required, is_for_aircraft_reservation_type)
                VALUES (?::uuid, ?::uuid, ?, ?, false, false, false, false, false, true,
                        true, true, false, false, false)
                """,
                id.toString(), clubId.toString(), name, code);
        return id;
    }

    private UUID seedPerson(String lastname, String firstname) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO t_person (id, firstname, lastname) VALUES (?::uuid, ?, ?)",
                id.toString(), firstname, lastname);
        return id;
    }

    private UUID seedFlight(UUID clubId, UUID aircraftId, LocalDate flightDate,
                           Instant start, Instant ldg, UUID startLocation, UUID ldgLocation,
                           UUID flightType) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO t_flight (id, operating_club_id, aircraft_id, flight_aircraft_type_id,
                        flight_date, start_date_time, ldg_date_time, start_location_id,
                        ldg_location_id, flight_type_id, is_solo_flight, no_start_time_information,
                        no_ldg_time_information, process_state_id, nr_of_ldgs, nr_of_ldgs_on_start_location)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?, ?::uuid, ?::uuid, ?::uuid,
                        false, false, false, ?::uuid, 1, 0)
                """,
                id.toString(), clubId.toString(), aircraftId.toString(), TYPE_GLIDER,
                flightDate, Timestamp.from(start), Timestamp.from(ldg),
                startLocation.toString(), ldgLocation.toString(), flightType.toString(),
                ch.alpenflight.flights.domain.FlightProcessState.VALID.id().toString());
        return id;
    }

    private UUID seedFlightWithStartType(UUID clubId, UUID aircraftId, LocalDate flightDate,
                           Instant start, Instant ldg, UUID startLocation, UUID ldgLocation,
                           UUID flightType, UUID startTypeId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO t_flight (id, operating_club_id, aircraft_id, flight_aircraft_type_id,
                        flight_date, start_date_time, ldg_date_time, start_location_id,
                        ldg_location_id, flight_type_id, start_type_id, is_solo_flight,
                        no_start_time_information, no_ldg_time_information, process_state_id,
                        nr_of_ldgs, nr_of_ldgs_on_start_location)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?, ?::uuid, ?::uuid, ?::uuid, ?::uuid,
                        false, false, false, ?::uuid, 1, 0)
                """,
                id.toString(), clubId.toString(), aircraftId.toString(), TYPE_GLIDER,
                flightDate, Timestamp.from(start), Timestamp.from(ldg),
                startLocation.toString(), ldgLocation.toString(), flightType.toString(),
                startTypeId.toString(),
                ch.alpenflight.flights.domain.FlightProcessState.VALID.id().toString());
        return id;
    }

    private void seedCrew(UUID flightId, UUID personId, UUID crewTypeId) {
        jdbc.update("""
                INSERT INTO t_flight_crew (id, flight_id, person_id, flight_crew_type_id)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid)
                """,
                UUID.randomUUID().toString(), flightId.toString(),
                personId.toString(), crewTypeId.toString());
    }

    private ResponseEntity<String> post(String path, Object body, String token) {
        return rest.exchange(
                RequestEntity.post(URI.create(path))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body),
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
