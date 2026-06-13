package ch.alpenflight.flights.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.aircraft.domain.Aircraft;
import ch.alpenflight.aircraft.domain.AircraftRepository;
import ch.alpenflight.clubs.domain.ClubRepository;
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
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
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
 * Write-model ↔ read-model sync ITs for the flight-report read-model
 * (J-7 RM-1, ADR 0027 §2): every mutation goes through the PRODUCTION write
 * path (aggregate factories + {@link FlightRepository#save} under
 * {@link TenantTestContext#runAs} — ADR 0027 §3), and the assertions read the
 * projected {@link FlightReportRow} back through its tenant-scoped
 * repository. Covers create-with-decorations, time updates, crew
 * replacement (pilot + second-crew priority), tow link / unlink repair of
 * BOTH rows, soft-delete removal incl. counterpart repair, and tenant
 * isolation of the projected rows.
 */
class FlightReportProjectionIT extends PostgresIntegrationTest {

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
    @Autowired ClubRepository clubs;
    @Autowired CountryRepository countries;
    @Autowired ClubStateRepository clubStates;

    private UUID clubA;
    private UUID clubB;

    @BeforeEach
    void cleanAndSeedClubs() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, "IT_FRP_", "IT_FRP");
        fixture.seed();
        clubA = fixture.clubA();
        clubB = fixture.clubB();
    }

    @Test
    void createFlightWithCrew_projectsRowWithDecorationsAndDuration() {
        UUID aircraft = seedAircraft(clubA);
        UUID start = seedLocation(clubA, "Birrfeld");
        UUID ldg = seedLocation(clubA, "Schaenis");
        UUID flightType = seedFlightType(clubA, "Schul", "SCH");
        UUID pilot = seedPerson("Tester", "Anna");
        UUID copilot = seedPerson("Berg", "Beat");

        Instant startTime = Instant.parse("2026-05-15T08:00:00Z");
        Instant ldgTime = Instant.parse("2026-05-15T09:30:00Z");
        UUID flightId = seedGlider(clubA, aircraft, LocalDate.of(2026, 5, 15),
                startTime, ldgTime, start, ldg, flightType, WINCH_LAUNCH,
                List.of(crew(pilot, FlightCrewTypeIds.PILOT_OR_STUDENT),
                        crew(copilot, FlightCrewTypeIds.CO_PILOT)));

        FlightReportRow row = rowOf(flightId);
        assertThat(row.getFlightAircraftType()).isEqualTo(FlightAircraftType.GLIDER);
        assertThat(row.getFlightDate()).isEqualTo(LocalDate.of(2026, 5, 15));
        assertThat(row.getStartDateTime()).isEqualTo(startTime);
        assertThat(row.getLdgDateTime()).isEqualTo(ldgTime);
        assertThat(row.getDurationSeconds()).isEqualTo(5400L);
        assertThat(row.getProcessStateId()).isEqualTo(FlightProcessState.VALID.id());
        assertThat(row.isSoloFlight()).isFalse();
        assertThat(row.getStartTypeCode()).isEqualTo("WINCH_LAUNCH");
        assertThat(row.getImmatriculation()).startsWith("HB-");
        assertThat(row.getPilotName()).isEqualTo("Tester Anna");
        assertThat(row.getSecondCrewName()).isEqualTo("Berg Beat");
        assertThat(row.getFlightCode()).isEqualTo("SCH");
        assertThat(row.getFlightTypeName()).isEqualTo("Schul");
        assertThat(row.getStartLocationId()).isEqualTo(start);
        assertThat(row.getStartLocationName()).isEqualTo("Birrfeld");
        assertThat(row.getLdgLocationId()).isEqualTo(ldg);
        assertThat(row.getLdgLocationName()).isEqualTo("Schaenis");
        assertThat(row.getNrOfLdgs()).isEqualTo((short) 1);
        assertThat(row.getTowFlightId()).isNull();
        assertThat(row.getTowedGliderFlightId()).isNull();
        assertThat(row.getCrew()).hasSize(2);
        assertThat(row.getCrew())
                .extracting(c -> c.getPersonId() + "|" + c.getFlightCrewTypeId())
                .containsExactlyInAnyOrder(
                        pilot + "|" + FlightCrewTypeIds.PILOT_OR_STUDENT,
                        copilot + "|" + FlightCrewTypeIds.CO_PILOT);
    }

    @Test
    void updateTimes_refreshesRowInPlace() {
        UUID aircraft = seedAircraft(clubA);
        UUID loc = seedLocation(clubA, "Base");
        UUID flightType = seedFlightType(clubA, "T", "T");
        Instant startTime = Instant.parse("2026-05-15T08:00:00Z");
        UUID flightId = seedGlider(clubA, aircraft, LocalDate.of(2026, 5, 15),
                startTime, Instant.parse("2026-05-15T09:00:00Z"), loc, loc, flightType,
                WINCH_LAUNCH, List.of());

        assertThat(rowOf(flightId).getDurationSeconds()).isEqualTo(3600L);

        Instant laterLdg = Instant.parse("2026-05-15T10:00:00Z");
        TenantTestContext.runAs(clubA, () -> {
            Flight flight = flights.findByIdWithCrew(FlightId.of(flightId)).orElseThrow();
            flight.updateOperationalData(ops(LocalDate.of(2026, 5, 15), startTime, laterLdg,
                    loc, loc, flightType, WINCH_LAUNCH));
            flights.save(flight);
        });

        FlightReportRow row = rowOf(flightId);
        assertThat(row.getLdgDateTime()).isEqualTo(laterLdg);
        assertThat(row.getDurationSeconds()).isEqualTo(7200L);
    }

    @Test
    void replaceCrew_updatesPilotAndSecondCrewNames_byLegacyPriority() {
        UUID aircraft = seedAircraft(clubA);
        UUID loc = seedLocation(clubA, "Base");
        UUID flightType = seedFlightType(clubA, "T", "T");
        UUID pilot = seedPerson("Alt", "Otto");
        UUID flightId = seedGlider(clubA, aircraft, LocalDate.of(2026, 5, 15),
                Instant.parse("2026-05-15T08:00:00Z"), Instant.parse("2026-05-15T09:00:00Z"),
                loc, loc, flightType, WINCH_LAUNCH,
                List.of(crew(pilot, FlightCrewTypeIds.PILOT_OR_STUDENT)));

        assertThat(rowOf(flightId).getPilotName()).isEqualTo("Alt Otto");
        assertThat(rowOf(flightId).getSecondCrewName()).isNull();

        UUID newPilot = seedPerson("Neu", "Nina");
        UUID passenger = seedPerson("Gast", "Greta");
        UUID instructor = seedPerson("Lehrer", "Ida");
        TenantTestContext.runAs(clubA, () -> {
            Flight flight = flights.findByIdWithCrew(FlightId.of(flightId)).orElseThrow();
            flight.replaceCrew(List.of(
                    crew(newPilot, FlightCrewTypeIds.PILOT_OR_STUDENT),
                    crew(passenger, FlightCrewTypeIds.PASSENGER),
                    crew(instructor, FlightCrewTypeIds.FLIGHT_INSTRUCTOR)));
            flights.save(flight);
        });

        FlightReportRow row = rowOf(flightId);
        assertThat(row.getPilotName()).isEqualTo("Neu Nina");
        // Second-crew priority: FlightInstructor (legacy_int_id 3) beats
        // Passenger (4) — the oracle's order-by-legacy_int_id pick.
        assertThat(row.getSecondCrewName()).isEqualTo("Lehrer Ida");
        assertThat(row.getCrew()).hasSize(3);
    }

    @Test
    void linkTow_refreshesBothRows_andUnlinkClearsBoth() {
        UUID gliderAc = seedAircraft(clubA);
        UUID towAc = seedAircraft(clubA);
        UUID loc = seedLocation(clubA, "Schlepphome");
        UUID gliderType = seedFlightType(clubA, "Strecke", "STR");
        UUID towType = seedFlightType(clubA, "Schlepp", "TOW");
        UUID towPilot = seedPerson("Schlepp", "Pia");

        UUID towId = seedTow(clubA, towAc, LocalDate.of(2026, 5, 20),
                Instant.parse("2026-05-20T10:00:00Z"), Instant.parse("2026-05-20T10:12:00Z"),
                loc, loc, towType, AEROTOW,
                List.of(crew(towPilot, FlightCrewTypeIds.PILOT_OR_STUDENT)));
        UUID gliderId = seedGlider(clubA, gliderAc, LocalDate.of(2026, 5, 20),
                Instant.parse("2026-05-20T10:00:00Z"), Instant.parse("2026-05-20T13:00:00Z"),
                loc, loc, gliderType, AEROTOW, List.of());

        TenantTestContext.runAs(clubA, () -> {
            Flight glider = flights.findByIdWithCrew(FlightId.of(gliderId)).orElseThrow();
            Flight tow = flights.findByIdWithCrew(FlightId.of(towId)).orElseThrow();
            glider.linkTow(tow);
            flights.save(glider);
        });

        FlightReportRow gliderRow = rowOf(gliderId);
        assertThat(gliderRow.getTowFlightId()).isEqualTo(towId);
        assertThat(gliderRow.getTowStartDateTime())
                .isEqualTo(Instant.parse("2026-05-20T10:00:00Z"));
        assertThat(gliderRow.getTowLdgDateTime())
                .isEqualTo(Instant.parse("2026-05-20T10:12:00Z"));
        assertThat(gliderRow.getTowProcessStateId()).isEqualTo(FlightProcessState.VALID.id());
        assertThat(gliderRow.getTowImmatriculation()).startsWith("HB-");
        assertThat(gliderRow.getTowPilotName()).isEqualTo("Schlepp Pia");
        assertThat(gliderRow.getTowFlightCode()).isEqualTo("TOW");
        assertThat(gliderRow.getTowFlightTypeName()).isEqualTo("Schlepp");
        assertThat(gliderRow.getTowStartLocationName()).isEqualTo("Schlepphome");
        assertThat(gliderRow.getTowedGliderFlightId()).isNull();

        // Reverse id on the tow's own row.
        FlightReportRow towRow = rowOf(towId);
        assertThat(towRow.getTowedGliderFlightId()).isEqualTo(gliderId);
        assertThat(towRow.getTowFlightId()).isNull();

        // Unlink repairs BOTH rows — the tow side is only reachable through
        // the read-model's reverse columns (the aggregate forgot the link).
        TenantTestContext.runAs(clubA, () -> {
            Flight glider = flights.findByIdWithCrew(FlightId.of(gliderId)).orElseThrow();
            glider.unlinkTow();
            flights.save(glider);
        });
        assertThat(rowOf(gliderId).getTowFlightId()).isNull();
        assertThat(rowOf(gliderId).getTowPilotName()).isNull();
        assertThat(rowOf(towId).getTowedGliderFlightId()).isNull();
    }

    @Test
    void softDelete_removesRow_andRepairsTowCounterpart() {
        UUID gliderAc = seedAircraft(clubA);
        UUID towAc = seedAircraft(clubA);
        UUID loc = seedLocation(clubA, "Base");
        UUID flightType = seedFlightType(clubA, "T", "T");

        UUID towId = seedTow(clubA, towAc, LocalDate.of(2026, 5, 20),
                Instant.parse("2026-05-20T10:00:00Z"), Instant.parse("2026-05-20T10:12:00Z"),
                loc, loc, flightType, AEROTOW, List.of());
        UUID gliderId = seedGlider(clubA, gliderAc, LocalDate.of(2026, 5, 20),
                Instant.parse("2026-05-20T10:00:00Z"), Instant.parse("2026-05-20T13:00:00Z"),
                loc, loc, flightType, AEROTOW, List.of());
        TenantTestContext.runAs(clubA, () -> {
            Flight glider = flights.findByIdWithCrew(FlightId.of(gliderId)).orElseThrow();
            Flight tow = flights.findByIdWithCrew(FlightId.of(towId)).orElseThrow();
            glider.linkTow(tow);
            flights.save(glider);
        });
        assertThat(rowOf(towId).getTowedGliderFlightId()).isEqualTo(gliderId);

        TenantTestContext.runAs(clubA, () -> {
            Flight glider = flights.findByIdWithCrew(FlightId.of(gliderId)).orElseThrow();
            glider.softDelete(Instant.parse("2026-05-21T08:00:00Z"));
            flights.save(glider);
        });

        // The deleted glider's row is gone; the tow row's back-ref is repaired.
        TenantTestContext.runAs(clubA, () ->
                assertThat(rows.findByFlightId(gliderId)).isEmpty());
        assertThat(rowOf(towId).getTowedGliderFlightId()).isNull();
    }

    @Test
    void tenantIsolation_rowCarriesOperatingClub_andIsInvisibleCrossTenant() {
        UUID aircraft = seedAircraft(clubA);
        UUID loc = seedLocation(clubA, "Base");
        UUID flightType = seedFlightType(clubA, "T", "T");
        UUID pilot = seedPerson("Iso", "Iris");
        UUID flightId = seedGlider(clubA, aircraft, LocalDate.of(2026, 5, 15),
                Instant.parse("2026-05-15T08:00:00Z"), Instant.parse("2026-05-15T09:00:00Z"),
                loc, loc, flightType, WINCH_LAUNCH,
                List.of(crew(pilot, FlightCrewTypeIds.PILOT_OR_STUDENT)));

        // Visible under the operating club, invisible under another tenant.
        TenantTestContext.runAs(clubA, () ->
                assertThat(rows.findByFlightId(flightId)).isPresent());
        TenantTestContext.runAs(clubB, () ->
                assertThat(rows.findByFlightId(flightId)).isEmpty());

        // The persisted rows carry the flight's operating club (read-only
        // JDBC assert of the stamped discriminator — not a seeding write).
        assertThat(jdbc.queryForObject(
                "SELECT operating_club_id FROM t_flight_report_row WHERE flight_id = ?::uuid",
                UUID.class, flightId.toString())).isEqualTo(clubA);
        assertThat(jdbc.queryForObject(
                "SELECT operating_club_id FROM t_flight_report_crew WHERE flight_id = ?::uuid",
                UUID.class, flightId.toString())).isEqualTo(clubA);
    }

    // ---------------------------------------------------------------- helpers
    //
    // Seeding goes through production code — domain factories + repositories
    // under TenantTestContext.runAs (ADR 0027 §3); read-only JDBC only for
    // reference-data id lookups + the stamped-discriminator assert.

    private FlightReportRow rowOf(UUID flightId) {
        return TenantTestContext.runAs(clubA, () -> rows.findByFlightId(flightId)
                .orElseThrow(() -> new AssertionError("no report row for flight " + flightId)));
    }

    private static CrewMemberSpec crew(UUID personId, UUID crewTypeId) {
        return new CrewMemberSpec(personId, crewTypeId, null, null, null, null, null, null);
    }

    private UUID seedGlider(UUID clubId, UUID aircraftId, LocalDate date, Instant start,
                            Instant ldg, UUID startLocation, UUID ldgLocation, UUID flightType,
                            UUID startTypeId, List<CrewMemberSpec> crewSpecs) {
        return seedFlight(clubId, FlightAircraftType.GLIDER, aircraftId, date, start, ldg,
                startLocation, ldgLocation, flightType, startTypeId, crewSpecs);
    }

    private UUID seedTow(UUID clubId, UUID aircraftId, LocalDate date, Instant start,
                         Instant ldg, UUID startLocation, UUID ldgLocation, UUID flightType,
                         UUID startTypeId, List<CrewMemberSpec> crewSpecs) {
        return seedFlight(clubId, FlightAircraftType.TOW, aircraftId, date, start, ldg,
                startLocation, ldgLocation, flightType, startTypeId, crewSpecs);
    }

    private UUID seedFlight(UUID clubId, FlightAircraftType type, UUID aircraftId,
                            LocalDate date, Instant start, Instant ldg, UUID startLocation,
                            UUID ldgLocation, UUID flightType, UUID startTypeId,
                            List<CrewMemberSpec> crewSpecs) {
        FlightOperationalData ops = ops(date, start, ldg, startLocation, ldgLocation,
                flightType, startTypeId);
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

    private static FlightOperationalData ops(LocalDate date, Instant start, Instant ldg,
                                             UUID startLocation, UUID ldgLocation,
                                             UUID flightType, UUID startTypeId) {
        return new FlightOperationalData(
                date, start, ldg, null, null,
                startLocation, ldgLocation, null, null, null, null,
                flightType, startTypeId, (short) 1, (short) 0,
                false, false, null, null, null, null, null, null, null, null,
                false);
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
