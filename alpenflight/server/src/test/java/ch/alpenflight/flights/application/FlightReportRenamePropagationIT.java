package ch.alpenflight.flights.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.aircraft.domain.Aircraft;
import ch.alpenflight.aircraft.domain.AircraftRepository;
import ch.alpenflight.flights.domain.CrewMemberSpec;
import ch.alpenflight.flights.domain.Flight;
import ch.alpenflight.flights.domain.FlightAircraftType;
import ch.alpenflight.flights.domain.FlightCrewTypeIds;
import ch.alpenflight.flights.domain.FlightOperationalData;
import ch.alpenflight.flights.domain.FlightProcessState;
import ch.alpenflight.flights.domain.FlightReportRow;
import ch.alpenflight.flights.domain.FlightReportRowRepository;
import ch.alpenflight.flights.domain.FlightRepository;
import ch.alpenflight.flighttypes.domain.FlightType;
import ch.alpenflight.flighttypes.domain.FlightTypeRepository;
import ch.alpenflight.locations.domain.Location;
import ch.alpenflight.locations.domain.LocationRepository;
import ch.alpenflight.persons.domain.Person;
import ch.alpenflight.persons.domain.PersonRepository;
import ch.alpenflight.platform.id.FlightId;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Rename-propagation ITs for the flight-report read-model (J-7 RM-2,
 * ADR 0027 §2): every denormalized decoration string follows its source
 * aggregate. One test per source — aircraft immatriculation (incl. the
 * tow-block copy on the glider's row), person name (pilot + tow-pilot),
 * location name, flight-type name / code. Each mutation runs through the
 * PRODUCTION update path (load via the repository port, aggregate mutator,
 * {@code repository.save} — ADR 0027 §3), which publishes the source's
 * {@code *Saved} domain event; {@link FlightReportDecorationRefreshListener}
 * re-projects the affected rows in the same transaction.
 */
class FlightReportRenamePropagationIT extends PostgresIntegrationTest {

    private static final UUID CLUB_A = UUID.fromString("019e30c6-2c00-7001-8000-0000000000f5");
    private static final UUID CLUB_B = UUID.fromString("019e30c6-2c00-7001-8000-0000000000f6");

    /** {@code t_start_type} WINCH_LAUNCH / AEROTOW ids (V2 seed, fixed canonical UUIDs). */
    private static final UUID WINCH_LAUNCH =
            UUID.fromString("019e2e15-2c00-7fa0-8000-000000000fa0");
    private static final UUID AEROTOW =
            UUID.fromString("019e2e15-2c00-7fa1-8000-000000000fa1");

    @Autowired JdbcTemplate jdbc;
    @Autowired FlightRepository flights;
    @Autowired FlightReportRowRepository rows;
    @Autowired AircraftRepository aircraftRepository;
    @Autowired LocationRepository locations;
    @Autowired FlightTypeRepository flightTypes;
    @Autowired PersonRepository persons;

    @BeforeEach
    void cleanAndSeedClubs() {
        new TwoClubFixture(jdbc, CLUB_A, CLUB_B, "IT_FRN_", "IT_FRN").seed();
    }

    @Test
    void aircraftRename_updatesOwnRow_andTowBlockOnTheGlidersRow() {
        UUID gliderAc = seedAircraft(CLUB_A);
        UUID towAc = seedAircraft(CLUB_A);
        UUID loc = seedLocation(CLUB_A, "Base");
        UUID flightType = seedFlightType(CLUB_A, "T", "T");
        UUID towId = seedTow(CLUB_A, towAc, loc, flightType, List.of());
        UUID gliderId = seedGlider(CLUB_A, gliderAc, loc, flightType, AEROTOW, List.of());
        linkTow(CLUB_A, gliderId, towId);

        // Production update path: load → aggregate mutator → save, under the
        // mutating principal's tenant (the listener's affected-flight lookup
        // rides @TenantId).
        TenantTestContext.runAs(CLUB_A, () -> {
            Aircraft tow = loadAircraft(towAc);
            tow.rename("HB-NEW1");
            aircraftRepository.save(tow);
            Aircraft glider = loadAircraft(gliderAc);
            glider.rename("HB-NEW2");
            aircraftRepository.save(glider);
        });

        // The tow's own row AND the glider row's denormalized tow block follow.
        assertThat(rowOf(gliderId).getTowImmatriculation()).isEqualTo("HB-NEW1");
        assertThat(rowOf(towId).getImmatriculation()).isEqualTo("HB-NEW1");
        assertThat(rowOf(gliderId).getImmatriculation()).isEqualTo("HB-NEW2");
    }

    @Test
    void personRename_updatesPilotAndTowPilotNames() {
        UUID gliderAc = seedAircraft(CLUB_A);
        UUID towAc = seedAircraft(CLUB_A);
        UUID loc = seedLocation(CLUB_A, "Base");
        UUID flightType = seedFlightType(CLUB_A, "T", "T");
        UUID pilot = seedPerson("Alt", "Anna");
        UUID towPilot = seedPerson("Schlepp", "Sepp");
        UUID towId = seedTow(CLUB_A, towAc, loc, flightType,
                List.of(crew(towPilot, FlightCrewTypeIds.PILOT_OR_STUDENT)));
        UUID gliderId = seedGlider(CLUB_A, gliderAc, loc, flightType, AEROTOW,
                List.of(crew(pilot, FlightCrewTypeIds.PILOT_OR_STUDENT)));
        linkTow(CLUB_A, gliderId, towId);
        assertThat(rowOf(gliderId).getPilotName()).isEqualTo("Alt Anna");
        assertThat(rowOf(gliderId).getTowPilotName()).isEqualTo("Schlepp Sepp");

        TenantTestContext.runAs(CLUB_A, () -> {
            Person p = persons.findActiveById(pilot).orElseThrow();
            p.rename("Anna", "Neu", null, null);
            persons.save(p);
            Person tp = persons.findActiveById(towPilot).orElseThrow();
            tp.rename("Sepp", "Zieher", null, null);
            persons.save(tp);
        });

        // "Lastname Firstname" — both the own-row pilot name and the
        // denormalized tow-pilot copy on the glider's row follow.
        assertThat(rowOf(gliderId).getPilotName()).isEqualTo("Neu Anna");
        assertThat(rowOf(towId).getPilotName()).isEqualTo("Zieher Sepp");
        assertThat(rowOf(gliderId).getTowPilotName()).isEqualTo("Zieher Sepp");
    }

    @Test
    void locationRename_updatesStartAndLdgNames() {
        UUID aircraft = seedAircraft(CLUB_A);
        UUID loc = seedLocation(CLUB_A, "Altfeld");
        UUID flightType = seedFlightType(CLUB_A, "T", "T");
        UUID flightId = seedGlider(CLUB_A, aircraft, loc, flightType, WINCH_LAUNCH, List.of());
        assertThat(rowOf(flightId).getStartLocationName()).isEqualTo("Altfeld");

        TenantTestContext.runAs(CLUB_A, () -> {
            Location location = locations.findActiveById(loc).orElseThrow();
            location.rename("Neufeld", null);
            locations.save(location);
        });

        FlightReportRow row = rowOf(flightId);
        assertThat(row.getStartLocationName()).isEqualTo("Neufeld");
        assertThat(row.getLdgLocationName()).isEqualTo("Neufeld");
        assertThat(row.getStartLocationId()).isEqualTo(loc);
    }

    @Test
    void flightTypeRename_updatesTypeNameAndCode() {
        UUID aircraft = seedAircraft(CLUB_A);
        UUID loc = seedLocation(CLUB_A, "Base");
        UUID flightType = seedFlightType(CLUB_A, "Schulung", "SCH");
        UUID flightId = seedGlider(CLUB_A, aircraft, loc, flightType, WINCH_LAUNCH, List.of());
        assertThat(rowOf(flightId).getFlightTypeName()).isEqualTo("Schulung");
        assertThat(rowOf(flightId).getFlightCode()).isEqualTo("SCH");

        TenantTestContext.runAs(CLUB_A, () -> {
            FlightType ft = flightTypes.findActiveById(flightType).orElseThrow();
            ft.rename("Weiterbildung");
            ft.changeFlightCode("WB");
            flightTypes.save(ft);
        });

        assertThat(rowOf(flightId).getFlightTypeName()).isEqualTo("Weiterbildung");
        assertThat(rowOf(flightId).getFlightCode()).isEqualTo("WB");
    }

    @Test
    void rename_doesNotDisturbOtherTenantsRows() {
        // The same physical-person rename must not damage another club's rows
        // (the crew-child lookup is not tenant-discriminated; out-of-tenant
        // ids must no-op, not delete).
        UUID pilot = seedPerson("Geteilt", "Greta");
        UUID acA = seedAircraft(CLUB_A);
        UUID locA = seedLocation(CLUB_A, "BaseA");
        UUID typeA = seedFlightType(CLUB_A, "TA", "TA");
        UUID flightA = seedGlider(CLUB_A, acA, locA, typeA, WINCH_LAUNCH,
                List.of(crew(pilot, FlightCrewTypeIds.PILOT_OR_STUDENT)));
        UUID acB = seedAircraft(CLUB_B);
        UUID locB = seedLocation(CLUB_B, "BaseB");
        UUID typeB = seedFlightType(CLUB_B, "TB", "TB");
        UUID flightB = seedGlider(CLUB_B, acB, locB, typeB, WINCH_LAUNCH,
                List.of(crew(pilot, FlightCrewTypeIds.PILOT_OR_STUDENT)));

        // Rename under club A's tenant context (a club-A admin's request).
        TenantTestContext.runAs(CLUB_A, () -> {
            Person p = persons.findActiveById(pilot).orElseThrow();
            p.rename("Greta", "Umbenannt", null, null);
            persons.save(p);
        });

        assertThat(rowOf(flightA).getPilotName()).isEqualTo("Umbenannt Greta");
        // Club B's row survives intact. Its name stays the OLD string inside
        // club A's transaction (cross-tenant rows are structurally out of
        // reach under @TenantId — documented RM-2 limitation, repaired by the
        // club's next flight save or rebuild).
        FlightReportRow rowB = TenantTestContext.runAs(CLUB_B,
                () -> rows.findByFlightId(flightB).orElseThrow());
        assertThat(rowB.getPilotName()).isEqualTo("Geteilt Greta");
    }

    // ---------------------------------------------------------------- helpers
    //
    // Seeding goes through production code — domain factories + repositories
    // under TenantTestContext.runAs (ADR 0027 §3); read-only JDBC only for
    // reference-data id lookups.

    private FlightReportRow rowOf(UUID flightId) {
        return TenantTestContext.runAs(CLUB_A, () -> rows.findByFlightId(flightId)
                .orElseThrow(() -> new AssertionError("no report row for flight " + flightId)));
    }

    private Aircraft loadAircraft(UUID aircraftId) {
        return aircraftRepository.findActiveById(aircraftId).orElseThrow();
    }

    private void linkTow(UUID clubId, UUID gliderId, UUID towId) {
        TenantTestContext.runAs(clubId, () -> {
            Flight glider = flights.findByIdWithCrew(FlightId.of(gliderId)).orElseThrow();
            Flight tow = flights.findByIdWithCrew(FlightId.of(towId)).orElseThrow();
            glider.linkTow(tow);
            flights.save(glider);
        });
    }

    private static CrewMemberSpec crew(UUID personId, UUID crewTypeId) {
        return new CrewMemberSpec(personId, crewTypeId, null, null, null, null, null, null);
    }

    private UUID seedGlider(UUID clubId, UUID aircraftId, UUID loc, UUID flightType,
                            UUID startTypeId, List<CrewMemberSpec> crewSpecs) {
        return seedFlight(clubId, FlightAircraftType.GLIDER, aircraftId, loc, flightType,
                startTypeId, crewSpecs);
    }

    private UUID seedTow(UUID clubId, UUID aircraftId, UUID loc, UUID flightType,
                         List<CrewMemberSpec> crewSpecs) {
        return seedFlight(clubId, FlightAircraftType.TOW, aircraftId, loc, flightType,
                AEROTOW, crewSpecs);
    }

    private UUID seedFlight(UUID clubId, FlightAircraftType type, UUID aircraftId,
                            UUID loc, UUID flightType, UUID startTypeId,
                            List<CrewMemberSpec> crewSpecs) {
        FlightOperationalData ops = new FlightOperationalData(
                LocalDate.of(2026, 5, 20),
                Instant.parse("2026-05-20T10:00:00Z"), Instant.parse("2026-05-20T11:00:00Z"),
                null, null,
                loc, loc, null, null, null, null,
                flightType, startTypeId, (short) 1, (short) 0,
                false, false, null, null, null, null, null, null, null, null,
                false);
        return TenantTestContext.runAs(clubId, () -> {
            Flight flight = switch (type) {
                case GLIDER -> Flight.createGlider(aircraftId, FlightProcessState.VALID.id(), ops);
                case TOW -> Flight.createTow(aircraftId, FlightProcessState.VALID.id(), ops);
                case MOTOR -> Flight.createMotor(aircraftId, FlightProcessState.VALID.id(), ops);
            };
            flight.replaceCrew(crewSpecs);
            return flights.save(flight).getId();
        });
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
}
