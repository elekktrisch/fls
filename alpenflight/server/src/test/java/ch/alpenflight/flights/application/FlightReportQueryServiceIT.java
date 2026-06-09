package ch.alpenflight.flights.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.flights.application.FlightReportDtos.FlightReportDataRecord;
import ch.alpenflight.flights.application.FlightReportDtos.FlightReportFilter;
import ch.alpenflight.flights.application.FlightReportDtos.FlightReportResult;
import ch.alpenflight.flights.domain.FlightCategory;
import ch.alpenflight.flights.domain.FlightCrewTypeIds;
import ch.alpenflight.flights.domain.FlightProcessState;
import ch.alpenflight.platform.id.FlightId;
import ch.alpenflight.platform.id.LocationId;
import ch.alpenflight.platform.id.PersonId;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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

    private TwoClubFixture clubs;

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

    // ---------------------------------------------------------------- helpers

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
        UUID id = UUID.randomUUID();
        UUID acType = jdbc.queryForObject("SELECT id FROM t_aircraft_type LIMIT 1", UUID.class);
        jdbc.update("""
                INSERT INTO t_aircraft (id, managing_club_id, owner_club_id, aircraft_type_id,
                                      immatriculation, is_towing_or_winch_required,
                                      is_towing_start_allowed, is_winch_start_allowed,
                                      is_towing_aircraft, is_fast_entry_record, nr_of_seats)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, false, false, false, false, false, 2)
                """,
                id.toString(), managingClubId.toString(), managingClubId.toString(),
                acType.toString(), "HB-" + id.toString().substring(0, 6).toUpperCase(java.util.Locale.ROOT));
        return id;
    }

    private UUID seedLocation(UUID clubId, String name) {
        UUID id = UUID.randomUUID();
        // location_type_id + country_id FKs → pick any seeded reference rows.
        UUID locType = jdbc.queryForObject("SELECT id FROM t_location_type LIMIT 1", UUID.class);
        UUID country = jdbc.queryForObject("SELECT id FROM t_country LIMIT 1", UUID.class);
        jdbc.update("""
                INSERT INTO t_location (id, club_id, location_name, location_type_id, country_id,
                                        is_inbound_route_required, is_outbound_route_required,
                                        is_fast_entry_record)
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

    @SuppressWarnings("ParameterNumber")
    private UUID seedFlight(UUID clubId, int aircraftTypeId, UUID aircraftId,
                           LocalDate flightDate, Instant start, Instant ldg,
                           UUID startLocation, UUID ldgLocation, UUID flightType,
                           UUID processStateId, UUID towFlightId, boolean solo) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO t_flight (id, operating_club_id, aircraft_id, flight_aircraft_type_id,
                        flight_date, start_date_time, ldg_date_time, start_location_id,
                        ldg_location_id, flight_type_id, is_solo_flight, tow_flight_id,
                        no_start_time_information, no_ldg_time_information, process_state_id)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?, ?::uuid, ?::uuid, ?::uuid,
                        ?, ?::uuid, false, false, ?::uuid)
                """,
                id.toString(), clubId.toString(), aircraftId.toString(), aircraftTypeId,
                flightDate, java.sql.Timestamp.from(start), java.sql.Timestamp.from(ldg),
                startLocation.toString(), ldgLocation.toString(), flightType.toString(),
                solo, towFlightId == null ? null : towFlightId.toString(),
                processStateId.toString());
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
}
