package ch.alpenflight.flights.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.aircraft.domain.Aircraft;
import ch.alpenflight.aircraft.domain.AircraftRepository;
import ch.alpenflight.flights.application.FlightReportDtos.FlightReportDataRecord;
import ch.alpenflight.flights.application.FlightReportDtos.FlightReportFilter;
import ch.alpenflight.flights.application.FlightReportDtos.FlightReportResult;
import ch.alpenflight.flights.domain.CrewMemberSpec;
import ch.alpenflight.flights.domain.Flight;
import ch.alpenflight.flights.domain.FlightCategory;
import ch.alpenflight.flights.domain.FlightCrew;
import ch.alpenflight.flights.domain.FlightCrewTypeIds;
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
import ch.alpenflight.platform.id.LocationId;
import ch.alpenflight.platform.id.PersonId;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Service-layer ITs for the J-7 T-03 flight-report read model. Drives
 * {@link FlightReportQueryService} under a real Postgres + the production
 * tenant carrier ({@link TenantTestContext#runAs}). Proves: a filtered query
 * returns the right rows; tenant isolation (club-B flight never returned to
 * club-A — the J-7 tenancy-hole correction); the aerotow nested-tow shape.
 *
 * <p>The controller surface is T-05; this stays at the query-service layer.
 */
class FlightReportQueryServiceIT extends PostgresIntegrationTest {

    private static final UUID CLUB_A = UUID.fromString("019e30c4-2c00-7001-8000-0000000000a1");
    private static final UUID CLUB_B = UUID.fromString("019e30c4-2c00-7001-8000-0000000000a2");

    private static final int TYPE_GLIDER = 1;
    private static final int TYPE_TOW = 2;
    private static final int TYPE_MOTOR = 4;

    @Autowired FlightReportQueryService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired FlightRepository flights;
    @Autowired AircraftRepository aircraftRepository;
    @Autowired LocationRepository locations;
    @Autowired FlightTypeRepository flightTypes;
    @Autowired PersonRepository persons;

    private TwoClubFixture clubs;

    /** Club each seeded flight lives under — lets {@link #seedCrew} re-load it via the tenant-scoped repository. */
    private final Map<UUID, UUID> flightClubs = new HashMap<>();

    @BeforeEach
    void seedClubs() {
        clubs = new TwoClubFixture(jdbc, CLUB_A, CLUB_B, "IT_FRQ_", "IT_FRQ");
        clubs.seed();
    }

    @Test
    void filteredQuery_returnsMatchingRows_withDecorationsAndDuration() {
        UUID aircraft = seedAircraft(CLUB_A);
        UUID location = seedLocation(CLUB_A, "Birrfeld");
        UUID flightType = seedFlightType(CLUB_A, "Schul", "SCH");
        UUID pilot = seedPerson("Tester", "Anna");
        UUID copilot = seedPerson("Berg", "Beat");

        Instant start = Instant.parse("2026-05-15T08:00:00Z");
        Instant ldg = Instant.parse("2026-05-15T09:30:00Z");
        UUID gliderInWindow = seedFlight(CLUB_A, TYPE_GLIDER, aircraft,
                LocalDate.of(2026, 5, 15), start, ldg, location, location, flightType,
                FlightProcessState.VALID.id(), null, true);
        seedCrew(gliderInWindow, pilot, FlightCrewTypeIds.PILOT_OR_STUDENT);
        seedCrew(gliderInWindow, copilot, FlightCrewTypeIds.CO_PILOT);

        // Outside the date window — must be excluded.
        seedFlight(CLUB_A, TYPE_GLIDER, aircraft,
                LocalDate.of(2026, 1, 1), Instant.parse("2026-01-01T08:00:00Z"),
                Instant.parse("2026-01-01T09:00:00Z"), location, location, flightType,
                FlightProcessState.VALID.id(), null, false);
        // A MOTOR flight in the window — excluded because motor flag is off.
        seedFlight(CLUB_A, TYPE_MOTOR, aircraft,
                LocalDate.of(2026, 5, 16), start, ldg, location, location, flightType,
                FlightProcessState.VALID.id(), null, false);

        FlightReportFilter filter = new FlightReportFilter(
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31),
                null, null, true, false, false);

        FlightReportResult result = TenantTestContext.runAs(CLUB_A,
                () -> service.getReportPage(filter, 0, 100, false, true));

        assertThat(result.totalRows()).isEqualTo(1);
        assertThat(result.items()).hasSize(1);
        assertThat(result.summaries()).isEmpty();

        FlightReportDataRecord row = result.items().get(0);
        assertThat(row.flightId()).isEqualTo(FlightId.of(gliderInWindow));
        assertThat(row.pilotName()).isEqualTo("Tester Anna");
        assertThat(row.secondCrewName()).isEqualTo("Berg Beat");
        assertThat(row.immatriculation()).isNotBlank();
        assertThat(row.flightCode()).isEqualTo("SCH");
        assertThat(row.flightTypeName()).isEqualTo("Schul");
        assertThat(row.startLocation()).isEqualTo("Birrfeld");
        assertThat(row.ldgLocation()).isEqualTo("Birrfeld");
        assertThat(row.flightCategory()).isEqualTo(FlightCategory.GLIDER);
        assertThat(row.isSoloFlight()).isTrue();
        assertThat(row.processState()).isEqualTo(FlightProcessState.VALID.legacyCode());
        assertThat(row.flightDuration()).isEqualTo(java.time.Duration.ofMinutes(90));
        assertThat(row.towFlight()).isNull();
        assertThat(row.towedGliderFlightId()).isNull();
    }

    @Test
    void tenantIsolation_clubBFlight_neverReturnedToClubA() {
        UUID aircraftA = seedAircraft(CLUB_A);
        UUID aircraftB = seedAircraft(CLUB_B);
        // Club B uses club A's location id as its filter target — the cross-club
        // case. The legacy tenancy hole would leak B's flight; the corrected
        // query scopes by tenant so A sees only A's flight.
        UUID locationA = seedLocation(CLUB_A, "Homebase");
        UUID flightTypeA = seedFlightType(CLUB_A, "TypeA", "TA");
        UUID flightTypeB = seedFlightType(CLUB_B, "TypeB", "TB");

        Instant start = Instant.parse("2026-05-15T08:00:00Z");
        Instant ldg = Instant.parse("2026-05-15T09:00:00Z");
        UUID flightA = seedFlight(CLUB_A, TYPE_GLIDER, aircraftA,
                LocalDate.of(2026, 5, 15), start, ldg, locationA, locationA, flightTypeA,
                FlightProcessState.VALID.id(), null, false);
        UUID flightB = seedFlight(CLUB_B, TYPE_GLIDER, aircraftB,
                LocalDate.of(2026, 5, 15), start, ldg, locationA, locationA, flightTypeB,
                FlightProcessState.VALID.id(), null, false);

        // Club A, filtering by its own location, sees only its flight.
        FlightReportFilter filterA = new FlightReportFilter(null, null, null,
                new LocationId(locationA), true, true, true);
        FlightReportResult aResult = TenantTestContext.runAs(CLUB_A,
                () -> service.getReportPage(filterA, 0, 100, false, true));
        assertThat(ids(aResult)).containsExactly(flightA);

        // Club B, scoped to B, sees only its flight (and not A's).
        FlightReportResult bResult = TenantTestContext.runAs(CLUB_B,
                () -> service.getReportPage(
                        new FlightReportFilter(null, null, null, null, true, true, true),
                        0, 100, false, true));
        assertThat(ids(bResult)).containsExactly(flightB);
    }

    @Test
    void aerotow_gliderRow_carriesNestedTowBlock_andTowHasBackRef() {
        UUID gliderAc = seedAircraft(CLUB_A);
        UUID towAc = seedAircraft(CLUB_A);
        UUID home = seedLocation(CLUB_A, "Schänis");
        UUID gliderType = seedFlightType(CLUB_A, "Streckenflug", "STR");
        UUID towType = seedFlightType(CLUB_A, "Schlepp", "TOW");
        UUID gliderPilot = seedPerson("Vogel", "Vera");
        UUID towPilot = seedPerson("Schlepp", "Sven");

        Instant towStart = Instant.parse("2026-05-20T10:00:00Z");
        Instant towLdg = Instant.parse("2026-05-20T10:12:00Z");
        UUID tow = seedFlight(CLUB_A, TYPE_TOW, towAc,
                LocalDate.of(2026, 5, 20), towStart, towLdg, home, home, towType,
                FlightProcessState.VALID.id(), null, false);
        seedCrew(tow, towPilot, FlightCrewTypeIds.PILOT_OR_STUDENT);

        Instant gliderStart = Instant.parse("2026-05-20T10:00:00Z");
        Instant gliderLdg = Instant.parse("2026-05-20T13:00:00Z");
        UUID glider = seedFlight(CLUB_A, TYPE_GLIDER, gliderAc,
                LocalDate.of(2026, 5, 20), gliderStart, gliderLdg, home, home, gliderType,
                FlightProcessState.VALID.id(), tow, false);
        seedCrew(glider, gliderPilot, FlightCrewTypeIds.PILOT_OR_STUDENT);

        // Glider + Tow flags on: both flights appear as rows.
        FlightReportFilter filter = new FlightReportFilter(
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31),
                null, null, true, false, true);
        FlightReportResult result = TenantTestContext.runAs(CLUB_A,
                () -> service.getReportPage(filter, 0, 100, false, true));

        assertThat(ids(result)).containsExactlyInAnyOrder(glider, tow);

        FlightReportDataRecord gliderRow = rowFor(result, glider);
        assertThat(gliderRow.towFlight()).isNotNull();
        assertThat(gliderRow.towFlight().towFlightId()).isEqualTo(FlightId.of(tow));
        assertThat(gliderRow.towFlight().pilotName()).isEqualTo("Schlepp Sven");
        assertThat(gliderRow.towFlight().flightCode()).isEqualTo("TOW");
        assertThat(gliderRow.towFlight().flightDuration())
                .isEqualTo(java.time.Duration.ofMinutes(12));
        assertThat(gliderRow.towedGliderFlightId()).isNull();

        // The tow as its own row carries the back-ref to the glider it towed.
        FlightReportDataRecord towRow = rowFor(result, tow);
        assertThat(towRow.flightCategory()).isEqualTo(FlightCategory.TOW);
        assertThat(towRow.towFlight()).isNull();
        assertThat(towRow.towedGliderFlightId()).isEqualTo(FlightId.of(glider));
    }

    // --- person-filter sanity (roles {PilotOrStudent,CoPilot,FlightInstructor}) ---

    @Test
    void personFilter_includesPilotRoles_excludesPassenger() {
        UUID aircraft = seedAircraft(CLUB_A);
        UUID location = seedLocation(CLUB_A, "Loc");
        UUID flightType = seedFlightType(CLUB_A, "T", "T");
        UUID person = seedPerson("Filter", "Felix");

        Instant start = Instant.parse("2026-05-15T08:00:00Z");
        Instant ldg = Instant.parse("2026-05-15T09:00:00Z");
        UUID asPilot = seedFlight(CLUB_A, TYPE_GLIDER, aircraft,
                LocalDate.of(2026, 5, 15), start, ldg, location, location, flightType,
                FlightProcessState.VALID.id(), null, false);
        seedCrew(asPilot, person, FlightCrewTypeIds.PILOT_OR_STUDENT);

        UUID asPassenger = seedFlight(CLUB_A, TYPE_GLIDER, aircraft,
                LocalDate.of(2026, 5, 16), start, ldg, location, location, flightType,
                FlightProcessState.VALID.id(), null, false);
        seedCrew(asPassenger, person, FlightCrewTypeIds.PASSENGER);

        FlightReportFilter filter = new FlightReportFilter(null, null,
                new PersonId(person), null, true, true, true);
        FlightReportResult result = TenantTestContext.runAs(CLUB_A,
                () -> service.getReportPage(filter, 0, 100, false, true));

        assertThat(ids(result)).containsExactly(asPilot);
    }

    // -------------------------------------------------------- summary (T-04)

    @Test
    void personBranch_groupsByCrewFunction_correctsTotalFlights_andSplitsInstructorSolo() {
        UUID glider = seedAircraft(CLUB_A);
        UUID motor = seedAircraft(CLUB_A);
        UUID tow = seedAircraft(CLUB_A);
        UUID loc = seedLocation(CLUB_A, "Base");
        UUID ft = seedFlightType(CLUB_A, "T", "T");
        UUID person = seedPerson("Pic", "Paul");
        UUID other = seedPerson("Co", "Carla");

        Instant s = Instant.parse("2026-05-15T08:00:00Z");
        Instant l = Instant.parse("2026-05-15T09:00:00Z"); // 60 min each

        // Pilot (Glider): 2 flights, ldgs 2 + 1 (with onStart 1 on one).
        UUID g1 = seedFlight(CLUB_A, TYPE_GLIDER, glider, LocalDate.of(2026, 5, 15), s, l,
                loc, loc, ft, FlightProcessState.VALID.id(), null, false, (short) 2, (short) 0);
        seedCrew(g1, person, FlightCrewTypeIds.PILOT_OR_STUDENT);
        UUID g2 = seedFlight(CLUB_A, TYPE_GLIDER, glider, LocalDate.of(2026, 5, 16), s, l,
                loc, loc, ft, FlightProcessState.VALID.id(), null, false, (short) 1, (short) 1);
        seedCrew(g2, person, FlightCrewTypeIds.PILOT_OR_STUDENT);

        // Pilot (Motor): 1 flight as pilot.
        UUID m1 = seedFlight(CLUB_A, TYPE_MOTOR, motor, LocalDate.of(2026, 5, 17), s, l,
                loc, loc, ft, FlightProcessState.VALID.id(), null, false, (short) 3, (short) 0);
        seedCrew(m1, person, FlightCrewTypeIds.PILOT_OR_STUDENT);

        // Pilot (Towing): 1 flight as pilot.
        UUID t1 = seedFlight(CLUB_A, TYPE_TOW, tow, LocalDate.of(2026, 5, 18), s, l,
                loc, loc, ft, FlightProcessState.VALID.id(), null, false, (short) 4, (short) 0);
        seedCrew(t1, person, FlightCrewTypeIds.PILOT_OR_STUDENT);

        // Copilot: 1 flight where person is copilot.
        UUID c1 = seedFlight(CLUB_A, TYPE_GLIDER, glider, LocalDate.of(2026, 5, 19), s, l,
                loc, loc, ft, FlightProcessState.VALID.id(), null, false, (short) 1, (short) 0);
        seedCrew(c1, other, FlightCrewTypeIds.PILOT_OR_STUDENT);
        seedCrew(c1, person, FlightCrewTypeIds.CO_PILOT);

        // Instructor (non-solo) + Instructor (solo) split.
        UUID i1 = seedFlight(CLUB_A, TYPE_GLIDER, glider, LocalDate.of(2026, 5, 20), s, l,
                loc, loc, ft, FlightProcessState.VALID.id(), null, false, (short) 1, (short) 0);
        seedCrew(i1, person, FlightCrewTypeIds.FLIGHT_INSTRUCTOR);
        UUID i2 = seedFlight(CLUB_A, TYPE_GLIDER, glider, LocalDate.of(2026, 5, 21), s, l,
                loc, loc, ft, FlightProcessState.VALID.id(), null, true, (short) 1, (short) 0);
        seedCrew(i2, person, FlightCrewTypeIds.FLIGHT_INSTRUCTOR);

        FlightReportFilter filter = new FlightReportFilter(
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31),
                new PersonId(person), null, true, true, true);
        FlightReportResult result = TenantTestContext.runAs(CLUB_A,
                () -> service.getReportPage(filter, 0, 100, false, true));

        List<FlightReportDtos.FlightReportSummary> sums = result.summaries();
        // Fixed order, each present (all have ≥1 flight) + Total.
        assertThat(sums.stream().map(FlightReportDtos.FlightReportSummary::groupBy))
                .containsExactly("Pilot (Glider)", "Pilot (Motor)", "Pilot (Towing)",
                        "Copilot", "Instructor", "Instructor (Soloflights)", "Total");

        var pg = summary(sums, "Pilot (Glider)");
        assertThat(pg.totalFlights()).isEqualTo(2);
        assertThat(pg.totalLdgs()).isEqualTo(2 + (1 + 1)); // (2) + (1 + onStart 1)
        assertThat(pg.totalStarts()).isEqualTo(2 + (1 + 1)); // starts base = nrOfLdgs
        assertThat(pg.totalFlightDuration()).isEqualTo(java.time.Duration.ofHours(2));

        // CORRECTED legacy bug: Motor/Towing pilot rows carry non-zero TotalFlights.
        assertThat(summary(sums, "Pilot (Motor)").totalFlights()).isEqualTo(1);
        assertThat(summary(sums, "Pilot (Towing)").totalFlights()).isEqualTo(1);

        // Instructor vs Instructor (Soloflights) split on IsSoloFlight.
        assertThat(summary(sums, "Instructor").totalFlights()).isEqualTo(1);
        assertThat(summary(sums, "Instructor (Soloflights)").totalFlights()).isEqualTo(1);
        assertThat(summary(sums, "Copilot").totalFlights()).isEqualTo(1);

        // Total = sum of all rows (8 flights across the 6 groups).
        var total = summary(sums, "Total");
        assertThat(total.totalFlights()).isEqualTo(2 + 1 + 1 + 1 + 1 + 1);
    }

    @Test
    void locationBranch_groupsByFlightTypeName_alphabetical_withTotal() {
        UUID ac = seedAircraft(CLUB_A);
        UUID loc = seedLocation(CLUB_A, "Home");
        UUID away = seedLocation(CLUB_A, "Away");
        UUID streckenflug = seedFlightType(CLUB_A, "Streckenflug", "STR");
        UUID schulung = seedFlightType(CLUB_A, "Schulung", "SCH");

        Instant s = Instant.parse("2026-05-15T08:00:00Z");
        Instant l = Instant.parse("2026-05-15T09:00:00Z");

        // Same-airfield flight (start==ldg==loc): starts = nrOfLdgs (3) + onStart (0).
        seedFlight(CLUB_A, TYPE_GLIDER, ac, LocalDate.of(2026, 5, 15), s, l,
                loc, loc, schulung, FlightProcessState.VALID.id(), null, false, (short) 3, (short) 0);
        // Fly-in (start away, ldg here): ldgs counts nrOfLdgs (2); starts term = nrOfLdgs-1 = 1.
        seedFlight(CLUB_A, TYPE_GLIDER, ac, LocalDate.of(2026, 5, 16), s, l,
                away, loc, streckenflug, FlightProcessState.VALID.id(), null, false, (short) 2, (short) 0);
        // Fly-out (start here, ldg away): ldgs 0 here; starts term = nrOfLdgs (1).
        seedFlight(CLUB_A, TYPE_GLIDER, ac, LocalDate.of(2026, 5, 17), s, l,
                loc, away, streckenflug, FlightProcessState.VALID.id(), null, false, (short) 1, (short) 0);

        FlightReportFilter filter = new FlightReportFilter(
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31),
                null, new LocationId(loc), true, true, true);
        FlightReportResult result = TenantTestContext.runAs(CLUB_A,
                () -> service.getReportPage(filter, 0, 100, false, true));

        List<FlightReportDtos.FlightReportSummary> sums = result.summaries();
        // Alphabetical FlightTypeName groups + Total appended.
        assertThat(sums.stream().map(FlightReportDtos.FlightReportSummary::groupBy))
                .containsExactly("Schulung", "Streckenflug", "Total");

        var sch = summary(sums, "Schulung");
        assertThat(sch.totalFlights()).isEqualTo(1);
        assertThat(sch.totalLdgs()).isEqualTo(3);   // ldg here
        assertThat(sch.totalStarts()).isEqualTo(3); // same-airfield: nrOfLdgs

        var str = summary(sums, "Streckenflug");
        assertThat(str.totalFlights()).isEqualTo(2);
        assertThat(str.totalLdgs()).isEqualTo(2);   // only the fly-in lands here
        assertThat(str.totalStarts()).isEqualTo((2 - 1) + 1); // fly-in (nrOfLdgs-1) + fly-out (nrOfLdgs)

        var total = summary(sums, "Total");
        assertThat(total.totalFlights()).isEqualTo(3);
        assertThat(total.totalLdgs()).isEqualTo(3 + 2);
        assertThat(total.totalStarts()).isEqualTo(3 + 1 + 1);
    }

    @Test
    void summaries_tenantScoped_clubBFlightsDoNotInflateClubASummary() {
        UUID acA = seedAircraft(CLUB_A);
        UUID acB = seedAircraft(CLUB_B);
        UUID locA = seedLocation(CLUB_A, "Shared");
        UUID ftA = seedFlightType(CLUB_A, "TypeX", "X");
        UUID ftB = seedFlightType(CLUB_B, "TypeX", "X");
        UUID person = seedPerson("Shared", "Sam");

        Instant s = Instant.parse("2026-05-15T08:00:00Z");
        Instant l = Instant.parse("2026-05-15T09:00:00Z");

        UUID fa = seedFlight(CLUB_A, TYPE_GLIDER, acA, LocalDate.of(2026, 5, 15), s, l,
                locA, locA, ftA, FlightProcessState.VALID.id(), null, false, (short) 1, (short) 0);
        seedCrew(fa, person, FlightCrewTypeIds.PILOT_OR_STUDENT);
        // Club B flight with the SAME person as pilot — must not leak into A's summary.
        UUID fb = seedFlight(CLUB_B, TYPE_GLIDER, acB, LocalDate.of(2026, 5, 15), s, l,
                locA, locA, ftB, FlightProcessState.VALID.id(), null, false, (short) 5, (short) 0);
        seedCrew(fb, person, FlightCrewTypeIds.PILOT_OR_STUDENT);

        FlightReportFilter filter = new FlightReportFilter(null, null,
                new PersonId(person), null, true, true, true);
        FlightReportResult aResult = TenantTestContext.runAs(CLUB_A,
                () -> service.getReportPage(filter, 0, 100, false, true));

        var pg = summary(aResult.summaries(), "Pilot (Glider)");
        assertThat(pg.totalFlights()).isEqualTo(1);          // only A's flight
        assertThat(pg.totalLdgs()).isEqualTo(1);             // B's 5 ldgs not counted
        assertThat(summary(aResult.summaries(), "Total").totalFlights()).isEqualTo(1);
    }

    private static FlightReportDtos.FlightReportSummary summary(
            List<FlightReportDtos.FlightReportSummary> sums, String label) {
        return sums.stream()
                .filter(x -> x.groupBy().equals(label))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no summary row '" + label + "'"));
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

    private static List<UUID> ids(FlightReportResult result) {
        return result.items().stream().map(r -> r.flightId().value()).toList();
    }

    private static FlightReportDataRecord rowFor(FlightReportResult result, UUID id) {
        return result.items().stream()
                .filter(r -> r.flightId().value().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for " + id));
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
        // location_type_id + country_id FKs → pick any seeded reference rows.
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

    @SuppressWarnings("ParameterNumber")
    private UUID seedFlight(UUID clubId, int aircraftTypeId, UUID aircraftId,
                           LocalDate flightDate, Instant start, Instant ldg,
                           UUID startLocation, UUID ldgLocation, UUID flightType,
                           UUID processStateId, UUID towFlightId, boolean solo) {
        return seedFlight(clubId, aircraftTypeId, aircraftId, flightDate, start, ldg,
                startLocation, ldgLocation, flightType, processStateId, towFlightId, solo,
                (Short) null, (Short) null);
    }

    @SuppressWarnings("ParameterNumber")
    private UUID seedFlight(UUID clubId, int aircraftTypeId, UUID aircraftId,
                           LocalDate flightDate, Instant start, Instant ldg,
                           UUID startLocation, UUID ldgLocation, UUID flightType,
                           UUID processStateId, UUID towFlightId, boolean solo,
                           Short nrOfLdgs, Short nrOfLdgsOnStartLocation) {
        FlightOperationalData ops = new FlightOperationalData(
                flightDate, start, ldg, null, null,
                startLocation, ldgLocation, null, null, null, null,
                flightType, null, nrOfLdgs, nrOfLdgsOnStartLocation,
                false, false, null, null, null, null, null, null, null, null,
                solo);
        return TenantTestContext.runAs(clubId, () -> {
            Flight flight = switch (aircraftTypeId) {
                case TYPE_TOW -> Flight.createTow(aircraftId, processStateId, ops);
                case TYPE_MOTOR -> Flight.createMotor(aircraftId, processStateId, ops);
                default -> Flight.createGlider(aircraftId, processStateId, ops);
            };
            if (towFlightId != null) {
                Flight towFlight = flights.findByIdWithCrew(FlightId.of(towFlightId))
                        .orElseThrow(() -> new AssertionError("no seeded tow " + towFlightId));
                flight.linkTow(towFlight);
            }
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
}
