package ch.alpenflight.flights.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.aircraft.domain.Aircraft;
import ch.alpenflight.aircraft.domain.AircraftRepository;
import ch.alpenflight.clubs.domain.ClubRepository;
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
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
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

class FlightReportQueryServiceIT extends PostgresIntegrationTest {

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
    @Autowired ClubRepository clubs;
    @Autowired CountryRepository countries;
    @Autowired ClubStateRepository clubStates;

    private UUID clubA;
    private UUID clubB;

    private final Map<UUID, UUID> flightClubs = new HashMap<>();

    @BeforeEach
    void seedClubs() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, "IT_FRQ_", "IT_FRQ");
        fixture.seed();
        clubA = fixture.clubA();
        clubB = fixture.clubB();
    }

    @Test
    void filteredQuery_returnsMatchingRows_withDecorationsAndDuration() {
        UUID aircraft = seedAircraft(clubA);
        UUID location = seedLocation(clubA, "Birrfeld");
        UUID flightType = seedFlightType(clubA, "Schul", "SCH");
        UUID pilot = seedPerson("Tester", "Anna");
        UUID copilot = seedPerson("Berg", "Beat");

        Instant start = Instant.parse("2026-05-15T08:00:00Z");
        Instant ldg = Instant.parse("2026-05-15T09:30:00Z");
        UUID gliderInWindow = seedFlight(clubA, TYPE_GLIDER, aircraft,
                LocalDate.of(2026, 5, 15), start, ldg, location, location, flightType,
                FlightProcessState.VALID.id(), null, true);
        seedCrew(gliderInWindow, pilot, FlightCrewTypeIds.PILOT_OR_STUDENT);
        seedCrew(gliderInWindow, copilot, FlightCrewTypeIds.CO_PILOT);

        UUID gliderOutsideTheDateWindow = seedFlight(clubA, TYPE_GLIDER, aircraft,
                LocalDate.of(2026, 1, 1), Instant.parse("2026-01-01T08:00:00Z"),
                Instant.parse("2026-01-01T09:00:00Z"), location, location, flightType,
                FlightProcessState.VALID.id(), null, false);
        UUID motorFlightTheMotorFlagExcludes = seedFlight(clubA, TYPE_MOTOR, aircraft,
                LocalDate.of(2026, 5, 16), start, ldg, location, location, flightType,
                FlightProcessState.VALID.id(), null, false);

        FlightReportFilter filter = new FlightReportFilter(
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31),
                null, null, true, false, false);

        FlightReportResult result = TenantTestContext.runAs(clubA,
                () -> service.getReportPage(filter, 0, 100, false, true));

        assertThat(result.totalRows()).isEqualTo(1);
        assertThat(ids(result))
                .as("excludes %s (outside the date window) and %s (motor flag off)",
                        gliderOutsideTheDateWindow, motorFlightTheMotorFlagExcludes)
                .containsExactly(gliderInWindow);
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
        UUID aircraftA = seedAircraft(clubA);
        UUID aircraftB = seedAircraft(clubB);
        UUID locationOwnedByClubA = seedLocation(clubA, "Homebase");
        UUID flightTypeA = seedFlightType(clubA, "TypeA", "TA");
        UUID flightTypeB = seedFlightType(clubB, "TypeB", "TB");

        Instant start = Instant.parse("2026-05-15T08:00:00Z");
        Instant ldg = Instant.parse("2026-05-15T09:00:00Z");
        UUID flightA = seedFlight(clubA, TYPE_GLIDER, aircraftA,
                LocalDate.of(2026, 5, 15), start, ldg,
                locationOwnedByClubA, locationOwnedByClubA, flightTypeA,
                FlightProcessState.VALID.id(), null, false);
        UUID flightB = seedFlight(clubB, TYPE_GLIDER, aircraftB,
                LocalDate.of(2026, 5, 15), start, ldg,
                locationOwnedByClubA, locationOwnedByClubA, flightTypeB,
                FlightProcessState.VALID.id(), null, false);

        FlightReportFilter filterA = new FlightReportFilter(null, null, null,
                new LocationId(locationOwnedByClubA), true, true, true);
        FlightReportResult aResult = TenantTestContext.runAs(clubA,
                () -> service.getReportPage(filterA, 0, 100, false, true));
        assertThat(ids(aResult))
                .as("club B flies from club A's location; the tenant scope still hides %s", flightB)
                .containsExactly(flightA);

        FlightReportResult bResult = TenantTestContext.runAs(clubB,
                () -> service.getReportPage(
                        new FlightReportFilter(null, null, null, null, true, true, true),
                        0, 100, false, true));
        assertThat(ids(bResult)).containsExactly(flightB);
    }

    @Test
    void aerotow_gliderRow_carriesNestedTowBlock_andTowHasBackRef() {
        UUID gliderAc = seedAircraft(clubA);
        UUID towAc = seedAircraft(clubA);
        UUID home = seedLocation(clubA, "Schänis");
        UUID gliderType = seedFlightType(clubA, "Streckenflug", "STR");
        UUID towType = seedFlightType(clubA, "Schlepp", "TOW");
        UUID gliderPilot = seedPerson("Vogel", "Vera");
        UUID towPilot = seedPerson("Schlepp", "Sven");

        Instant towStart = Instant.parse("2026-05-20T10:00:00Z");
        Instant towLdg = Instant.parse("2026-05-20T10:12:00Z");
        UUID tow = seedFlight(clubA, TYPE_TOW, towAc,
                LocalDate.of(2026, 5, 20), towStart, towLdg, home, home, towType,
                FlightProcessState.VALID.id(), null, false);
        seedCrew(tow, towPilot, FlightCrewTypeIds.PILOT_OR_STUDENT);

        Instant gliderStart = Instant.parse("2026-05-20T10:00:00Z");
        Instant gliderLdg = Instant.parse("2026-05-20T13:00:00Z");
        UUID glider = seedFlight(clubA, TYPE_GLIDER, gliderAc,
                LocalDate.of(2026, 5, 20), gliderStart, gliderLdg, home, home, gliderType,
                FlightProcessState.VALID.id(), tow, false);
        seedCrew(glider, gliderPilot, FlightCrewTypeIds.PILOT_OR_STUDENT);

        FlightReportFilter filter = new FlightReportFilter(
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31),
                null, null, true, false, true);
        FlightReportResult result = TenantTestContext.runAs(clubA,
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

        FlightReportDataRecord towRow = rowFor(result, tow);
        assertThat(towRow.flightCategory()).isEqualTo(FlightCategory.TOW);
        assertThat(towRow.towFlight()).isNull();
        assertThat(towRow.towedGliderFlightId()).isEqualTo(FlightId.of(glider));
    }


    @Test
    void personFilter_includesPilotRoles_excludesPassenger() {
        UUID aircraft = seedAircraft(clubA);
        UUID location = seedLocation(clubA, "Loc");
        UUID flightType = seedFlightType(clubA, "T", "T");
        UUID person = seedPerson("Filter", "Felix");

        Instant start = Instant.parse("2026-05-15T08:00:00Z");
        Instant ldg = Instant.parse("2026-05-15T09:00:00Z");
        UUID asPilot = seedFlight(clubA, TYPE_GLIDER, aircraft,
                LocalDate.of(2026, 5, 15), start, ldg, location, location, flightType,
                FlightProcessState.VALID.id(), null, false);
        seedCrew(asPilot, person, FlightCrewTypeIds.PILOT_OR_STUDENT);

        UUID asPassenger = seedFlight(clubA, TYPE_GLIDER, aircraft,
                LocalDate.of(2026, 5, 16), start, ldg, location, location, flightType,
                FlightProcessState.VALID.id(), null, false);
        seedCrew(asPassenger, person, FlightCrewTypeIds.PASSENGER);

        FlightReportFilter filter = new FlightReportFilter(null, null,
                new PersonId(person), null, true, true, true);
        FlightReportResult result = TenantTestContext.runAs(clubA,
                () -> service.getReportPage(filter, 0, 100, false, true));

        assertThat(ids(result)).containsExactly(asPilot);
    }


    @Test
    void personBranch_groupsByCrewFunction_correctsTotalFlights_andSplitsInstructorSolo() {
        UUID glider = seedAircraft(clubA);
        UUID motor = seedAircraft(clubA);
        UUID tow = seedAircraft(clubA);
        UUID loc = seedLocation(clubA, "Base");
        UUID ft = seedFlightType(clubA, "T", "T");
        UUID person = seedPerson("Pic", "Paul");
        UUID other = seedPerson("Co", "Carla");

        Instant s = Instant.parse("2026-05-15T08:00:00Z");
        Instant l = Instant.parse("2026-05-15T09:00:00Z");

        UUID g1 = seedFlight(clubA, TYPE_GLIDER, glider, LocalDate.of(2026, 5, 15), s, l,
                loc, loc, ft, FlightProcessState.VALID.id(), null, false, (short) 2, (short) 0);
        seedCrew(g1, person, FlightCrewTypeIds.PILOT_OR_STUDENT);
        UUID g2 = seedFlight(clubA, TYPE_GLIDER, glider, LocalDate.of(2026, 5, 16), s, l,
                loc, loc, ft, FlightProcessState.VALID.id(), null, false, (short) 1, (short) 1);
        seedCrew(g2, person, FlightCrewTypeIds.PILOT_OR_STUDENT);

        UUID m1 = seedFlight(clubA, TYPE_MOTOR, motor, LocalDate.of(2026, 5, 17), s, l,
                loc, loc, ft, FlightProcessState.VALID.id(), null, false, (short) 3, (short) 0);
        seedCrew(m1, person, FlightCrewTypeIds.PILOT_OR_STUDENT);

        UUID t1 = seedFlight(clubA, TYPE_TOW, tow, LocalDate.of(2026, 5, 18), s, l,
                loc, loc, ft, FlightProcessState.VALID.id(), null, false, (short) 4, (short) 0);
        seedCrew(t1, person, FlightCrewTypeIds.PILOT_OR_STUDENT);

        UUID c1 = seedFlight(clubA, TYPE_GLIDER, glider, LocalDate.of(2026, 5, 19), s, l,
                loc, loc, ft, FlightProcessState.VALID.id(), null, false, (short) 1, (short) 0);
        seedCrew(c1, other, FlightCrewTypeIds.PILOT_OR_STUDENT);
        seedCrew(c1, person, FlightCrewTypeIds.CO_PILOT);

        UUID i1 = seedFlight(clubA, TYPE_GLIDER, glider, LocalDate.of(2026, 5, 20), s, l,
                loc, loc, ft, FlightProcessState.VALID.id(), null, false, (short) 1, (short) 0);
        seedCrew(i1, person, FlightCrewTypeIds.FLIGHT_INSTRUCTOR);
        UUID i2 = seedFlight(clubA, TYPE_GLIDER, glider, LocalDate.of(2026, 5, 21), s, l,
                loc, loc, ft, FlightProcessState.VALID.id(), null, true, (short) 1, (short) 0);
        seedCrew(i2, person, FlightCrewTypeIds.FLIGHT_INSTRUCTOR);

        FlightReportFilter filter = new FlightReportFilter(
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31),
                new PersonId(person), null, true, true, true);
        FlightReportResult result = TenantTestContext.runAs(clubA,
                () -> service.getReportPage(filter, 0, 100, false, true));

        List<FlightReportDtos.FlightReportSummary> sums = result.summaries();
        assertThat(sums.stream().map(FlightReportDtos.FlightReportSummary::groupBy))
                .containsExactly("Pilot (Glider)", "Pilot (Motor)", "Pilot (Towing)",
                        "Copilot", "Instructor", "Instructor (Soloflights)", "Total");

        var pg = summary(sums, "Pilot (Glider)");
        assertThat(pg.totalFlights()).isEqualTo(2);
        assertThat(pg.totalLdgs()).isEqualTo(2 + (1 + 1));
        assertThat(pg.totalStarts()).isEqualTo(2 + (1 + 1));
        assertThat(pg.totalFlightDuration()).isEqualTo(java.time.Duration.ofHours(2));

        assertThat(summary(sums, "Pilot (Motor)").totalFlights())
                .as("corrected legacy bug: motor pilot rows carry a non-zero flight count")
                .isEqualTo(1);
        assertThat(summary(sums, "Pilot (Towing)").totalFlights())
                .as("corrected legacy bug: towing pilot rows carry a non-zero flight count")
                .isEqualTo(1);

        assertThat(summary(sums, "Instructor").totalFlights()).isEqualTo(1);
        assertThat(summary(sums, "Instructor (Soloflights)").totalFlights())
                .as("instructor rows split on IsSoloFlight")
                .isEqualTo(1);
        assertThat(summary(sums, "Copilot").totalFlights()).isEqualTo(1);

        var total = summary(sums, "Total");
        assertThat(total.totalFlights()).isEqualTo(2 + 1 + 1 + 1 + 1 + 1);
    }

    @Test
    void locationBranch_groupsByFlightTypeName_alphabetical_withTotal() {
        UUID ac = seedAircraft(clubA);
        UUID loc = seedLocation(clubA, "Home");
        UUID away = seedLocation(clubA, "Away");
        UUID streckenflug = seedFlightType(clubA, "Streckenflug", "STR");
        UUID schulung = seedFlightType(clubA, "Schulung", "SCH");

        Instant s = Instant.parse("2026-05-15T08:00:00Z");
        Instant l = Instant.parse("2026-05-15T09:00:00Z");

        UUID sameAirfieldFlight = seedFlight(clubA, TYPE_GLIDER, ac, LocalDate.of(2026, 5, 15), s, l,
                loc, loc, schulung, FlightProcessState.VALID.id(), null, false, (short) 3, (short) 0);
        UUID flyInFromAway = seedFlight(clubA, TYPE_GLIDER, ac, LocalDate.of(2026, 5, 16), s, l,
                away, loc, streckenflug, FlightProcessState.VALID.id(), null, false, (short) 2, (short) 0);
        UUID flyOutToAway = seedFlight(clubA, TYPE_GLIDER, ac, LocalDate.of(2026, 5, 17), s, l,
                loc, away, streckenflug, FlightProcessState.VALID.id(), null, false, (short) 1, (short) 0);

        FlightReportFilter filter = new FlightReportFilter(
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31),
                null, new LocationId(loc), true, true, true);
        FlightReportResult result = TenantTestContext.runAs(clubA,
                () -> service.getReportPage(filter, 0, 100, false, true));

        List<FlightReportDtos.FlightReportSummary> sums = result.summaries();
        assertThat(sums.stream().map(FlightReportDtos.FlightReportSummary::groupBy))
                .containsExactly("Schulung", "Streckenflug", "Total");

        var sch = summary(sums, "Schulung");
        assertThat(sch.totalFlights()).isEqualTo(1);
        assertThat(sch.totalLdgs()).isEqualTo(3);
        assertThat(sch.totalStarts())
                .as("same-airfield flight %s: starts == its nrOfLdgs", sameAirfieldFlight)
                .isEqualTo(3);

        var str = summary(sums, "Streckenflug");
        assertThat(str.totalFlights()).isEqualTo(2);
        assertThat(str.totalLdgs())
                .as("only the fly-in %s lands here; the fly-out %s does not",
                        flyInFromAway, flyOutToAway)
                .isEqualTo(2);
        assertThat(str.totalStarts())
                .as("fly-in %s contributes nrOfLdgs-1, fly-out %s contributes nrOfLdgs",
                        flyInFromAway, flyOutToAway)
                .isEqualTo((2 - 1) + 1);

        var total = summary(sums, "Total");
        assertThat(total.totalFlights()).isEqualTo(3);
        assertThat(total.totalLdgs()).isEqualTo(3 + 2);
        assertThat(total.totalStarts()).isEqualTo(3 + 1 + 1);
    }

    @Test
    void summaries_tenantScoped_clubBFlightsDoNotInflateClubASummary() {
        UUID acA = seedAircraft(clubA);
        UUID acB = seedAircraft(clubB);
        UUID locA = seedLocation(clubA, "Shared");
        UUID ftA = seedFlightType(clubA, "TypeX", "X");
        UUID ftB = seedFlightType(clubB, "TypeX", "X");
        UUID person = seedPerson("Shared", "Sam");

        Instant s = Instant.parse("2026-05-15T08:00:00Z");
        Instant l = Instant.parse("2026-05-15T09:00:00Z");

        UUID clubAFlight = seedFlight(clubA, TYPE_GLIDER, acA, LocalDate.of(2026, 5, 15), s, l,
                locA, locA, ftA, FlightProcessState.VALID.id(), null, false, (short) 1, (short) 0);
        seedCrew(clubAFlight, person, FlightCrewTypeIds.PILOT_OR_STUDENT);
        UUID clubBFlightWithTheSamePilot = seedFlight(clubB, TYPE_GLIDER, acB,
                LocalDate.of(2026, 5, 15), s, l,
                locA, locA, ftB, FlightProcessState.VALID.id(), null, false, (short) 5, (short) 0);
        seedCrew(clubBFlightWithTheSamePilot, person, FlightCrewTypeIds.PILOT_OR_STUDENT);

        FlightReportFilter filter = new FlightReportFilter(null, null,
                new PersonId(person), null, true, true, true);
        FlightReportResult aResult = TenantTestContext.runAs(clubA,
                () -> service.getReportPage(filter, 0, 100, false, true));

        var pg = summary(aResult.summaries(), "Pilot (Glider)");
        assertThat(pg.totalFlights()).isEqualTo(1);
        assertThat(pg.totalLdgs())
                .as("club B's 5 landings on %s stay out of club A's summary",
                        clubBFlightWithTheSamePilot)
                .isEqualTo(1);
        assertThat(summary(aResult.summaries(), "Total").totalFlights()).isEqualTo(1);
    }

    private static FlightReportDtos.FlightReportSummary summary(
            List<FlightReportDtos.FlightReportSummary> sums, String label) {
        return sums.stream()
                .filter(x -> x.groupBy().equals(label))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no summary row '" + label + "'"));
    }


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
