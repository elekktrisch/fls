package ch.alpenflight.flights.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.aircraft.domain.Aircraft;
import ch.alpenflight.aircraft.domain.AircraftRepository;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.flights.domain.CrewMemberSpec;
import ch.alpenflight.flights.domain.Flight;
import ch.alpenflight.flights.domain.FlightCrew;
import ch.alpenflight.flights.domain.FlightOperationalData;
import ch.alpenflight.flights.domain.FlightProcessState;
import ch.alpenflight.flights.domain.FlightRepository;
import ch.alpenflight.flighttypes.domain.FlightType;
import ch.alpenflight.flighttypes.domain.FlightTypeRepository;
import ch.alpenflight.locations.domain.Location;
import ch.alpenflight.locations.domain.LocationRepository;
import ch.alpenflight.persons.domain.Person;
import ch.alpenflight.persons.domain.PersonRepository;
import ch.alpenflight.platform.id.FlightId;
import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
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

    /** {@code t_start_type} WINCH_LAUNCH id (V2 seed) → legacy AircraftStartType.WinchLaunch int 2. */
    private static final UUID WINCH_LAUNCH_START_TYPE =
            UUID.fromString("019e2e15-2c00-7fa0-8000-000000000fa0");

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;
    @Autowired FlightRepository flights;
    @Autowired AircraftRepository aircraftRepository;
    @Autowired LocationRepository locations;
    @Autowired FlightTypeRepository flightTypes;
    @Autowired PersonRepository persons;
    @Autowired ClubRepository clubs;
    @Autowired CountryRepository countries;
    @Autowired ClubStateRepository clubStates;

    private UUID clubA;
    private UUID clubB;

    /** Club each seeded flight lives under — lets {@link #seedCrew} re-load it via the tenant-scoped repository. */
    private final Map<UUID, UUID> flightClubs = new HashMap<>();

    @BeforeEach
    void cleanAndSeedClubs() {
        // Shared Testcontainers DB — TwoClubFixture pre-cleans this IT's tenant
        // rows from prior runs (ADR 0021 pre-clean, FK-ordered) and re-inserts
        // the two clubs.
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, "IT_FRC_", "IT_FRC");
        fixture.seed();
        clubA = fixture.clubA();
        clubB = fixture.clubB();
    }

    @Test
    void pageQuery_clubAdmin_returnsItemsAndSummaries() {
        UUID aircraft = seedAircraft(clubA);
        UUID location = seedLocation(clubA, "Birrfeld");
        UUID flightType = seedFlightType(clubA, "Schul", "SCH");
        UUID pilot = seedPerson("Tester", "Anna");

        Instant start = Instant.parse("2026-05-15T08:00:00Z");
        Instant ldg = Instant.parse("2026-05-15T09:30:00Z");
        UUID flight = seedFlight(clubA, aircraft, LocalDate.of(2026, 5, 15), start, ldg,
                location, location, flightType);
        seedCrew(flight, pilot, FlightCrewTypeIds_PILOT);

        String token = mintToken(clubA, "CLUB_ADMINISTRATOR");
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
        UUID aircraftA = seedAircraft(clubA);
        UUID aircraftB = seedAircraft(clubB);
        UUID locationA = seedLocation(clubA, "HomeA");
        UUID ftA = seedFlightType(clubA, "TypeA", "TA");
        UUID ftB = seedFlightType(clubB, "TypeB", "TB");

        Instant start = Instant.parse("2026-05-15T08:00:00Z");
        Instant ldg = Instant.parse("2026-05-15T09:00:00Z");
        // Club A flight at locationA.
        seedFlight(clubA, aircraftA, LocalDate.of(2026, 5, 15), start, ldg,
                locationA, locationA, ftA);
        // Club B flight that ALSO references locationA (cross-club filter target).
        seedFlight(clubB, aircraftB, LocalDate.of(2026, 5, 15), start, ldg,
                locationA, locationA, ftB);

        String token = mintToken(clubA, "CLUB_ADMINISTRATOR");
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
        UUID aircraft = seedAircraft(clubA);
        UUID location = seedLocation(clubA, "Loc");
        UUID flightType = seedFlightType(clubA, "T", "T");
        Instant start = Instant.parse("2026-05-15T08:00:00Z");
        Instant ldg = Instant.parse("2026-05-15T09:00:00Z");
        seedFlight(clubA, aircraft, LocalDate.of(2026, 5, 15), start, ldg,
                location, location, flightType);

        // A low-privilege PILOT principal (the J-3 PILOT-403 lesson) must read.
        String token = mintToken(clubA, "PILOT");
        ResponseEntity<String> res = post("/api/v1/flightreports/page/0/100",
                Map.of(), token);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readJson(res).get("totalRows").asLong()).isEqualTo(1);
    }

    @Test
    void exportExcel_streamsXlsxWithLegacyLayout() throws Exception {
        UUID aircraft = seedAircraft(clubA);
        UUID location = seedLocation(clubA, "Birrfeld");
        UUID flightType = seedFlightType(clubA, "Schul", "SCH");
        UUID pilot = seedPerson("Tester", "Anna");

        Instant start = Instant.parse("2026-05-15T08:05:00Z");
        Instant ldg = Instant.parse("2026-05-15T09:35:00Z"); // 1h30m duration
        // WINCH_LAUNCH start type → legacy AircraftStartType.WinchLaunch int 2 (the StartType-int parity contract).
        UUID flight = seedFlightWithStartType(clubA, aircraft, LocalDate.of(2026, 5, 15),
                start, ldg, location, location, flightType, WINCH_LAUNCH_START_TYPE);
        seedCrew(flight, pilot, FlightCrewTypeIds_PILOT);

        String token = mintToken(clubA, "CLUB_ADMINISTRATOR");
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
            assertThat((int) data.getCell(13).getNumericCellValue()).isEqualTo(2); // StartType WINCH=2 (legacy AircraftStartType.WinchLaunch)
        }
    }

    // ---------------------------------------------------------------- helpers
    //
    // Seeding goes through production code — domain factories + their
    // repositories (J-7 review rider: no JDBC writes in tests). Tenant-scoped
    // saves run under TenantTestContext.runAs so Hibernate's @TenantId
    // resolver stamps the club, exactly as in production. Read-only JDBC
    // remains for reference-data id lookups, matching the testsupport sweep
    // factories. No reflection needed: every seeded attribute is reachable
    // through a production factory or method.

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

    private UUID seedAircraft(UUID managingClubId) {
        UUID acType = jdbc.queryForObject("SELECT id FROM t_aircraft_type LIMIT 1", UUID.class);
        String immatriculation = "HB-"
                + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        // Aircraft is cross-tenant (managing_club_id is an explicit factory arg,
        // no @TenantId) — no tenant scope needed for the save.
        Aircraft aircraft = Aircraft.register(managingClubId, managingClubId, acType,
                immatriculation, null, null, null, null, null, null, null, null, null, 2,
                null, null, null, null, null, false, false, false, false, null, null);
        return aircraftRepository.save(aircraft).getId().value();
    }

    private UUID seedLocation(UUID clubId, String name) {
        UUID locType = jdbc.queryForObject("SELECT id FROM t_location_type LIMIT 1", UUID.class);
        UUID country = jdbc.queryForObject("SELECT id FROM t_country LIMIT 1", UUID.class);
        Location location = Location.create(name, null, country, locType, null,
                null, null, null, null, null, null, null, null, null, null,
                false, false, false);
        return TenantTestContext.runAs(clubId,
                () -> locations.save(location).getId().value());
    }

    private UUID seedFlightType(UUID clubId, String name, String code) {
        FlightType flightType = FlightType.register(name, code,
                false, false, false, false, false,
                true, true, true,
                false, false, false, null);
        return TenantTestContext.runAs(clubId,
                () -> flightTypes.save(flightType).getId().value());
    }

    private UUID seedPerson(String lastname, String firstname) {
        // Person is cross-tenant (sacred cow) — saved outside any tenant scope.
        return persons.save(Person.register(firstname, lastname, null)).getId().value();
    }

    private UUID seedFlight(UUID clubId, UUID aircraftId, LocalDate flightDate,
                           Instant start, Instant ldg, UUID startLocation, UUID ldgLocation,
                           UUID flightType) {
        return seedFlightWithStartType(clubId, aircraftId, flightDate, start, ldg,
                startLocation, ldgLocation, flightType, null);
    }

    private UUID seedFlightWithStartType(UUID clubId, UUID aircraftId, LocalDate flightDate,
                           Instant start, Instant ldg, UUID startLocation, UUID ldgLocation,
                           UUID flightType, UUID startTypeId) {
        FlightOperationalData ops = new FlightOperationalData(
                flightDate, start, ldg, null, null,
                startLocation, ldgLocation, null, null, null, null,
                flightType, startTypeId, (short) 1, (short) 0,
                false, false, null, null, null, null, null, null, null, null,
                false);
        return TenantTestContext.runAs(clubId, () -> {
            Flight flight = Flight.createGlider(aircraftId, FlightProcessState.VALID.id(), ops);
            UUID id = flights.save(flight).getId();
            flightClubs.put(id, clubId);
            return id;
        });
    }

    private void seedCrew(UUID flightId, UUID personId, UUID crewTypeId) {
        TenantTestContext.runAs(flightClubs.get(flightId), () -> {
            Flight flight = flights.findByIdWithCrew(FlightId.of(flightId)).orElseThrow(
                    () -> new AssertionError("no seeded flight " + flightId));
            List<CrewMemberSpec> specs = new ArrayList<>();
            for (FlightCrew member : flight.getCrew()) {
                specs.add(new CrewMemberSpec(member.getPersonId(), member.getFlightCrewTypeId(),
                        member.getBeginFlightDatetime(), member.getEndFlightDatetime(),
                        member.getBeginInstructionDatetime(), member.getEndInstructionDatetime(),
                        member.getNrOfLdgs(), member.getNrOfStarts()));
            }
            specs.add(new CrewMemberSpec(personId, crewTypeId, null, null, null, null, null, null));
            flight.replaceCrew(specs);
            flights.save(flight);
        });
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
