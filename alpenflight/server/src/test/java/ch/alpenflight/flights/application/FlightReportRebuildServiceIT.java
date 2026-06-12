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
 * ITs for {@link FlightReportRebuildService} (J-7 RM-2, ADR 0027 §2): the
 * batch repair seam for flights that bypass {@code FlightRepository.save}
 * (migration ingest JDBC, SQL dev seeds, showcase base inserts). Seeding goes
 * through production code (ADR 0027 §3 — aggregate factories + repositories
 * under {@link TenantTestContext#runAs}); the BYPASS itself is then simulated
 * deliberately — rows deleted via the row repository, a flight soft-deleted
 * via raw JDBC — because "data changed without the projector noticing" is
 * exactly the condition the rebuild exists to repair.
 */
class FlightReportRebuildServiceIT extends PostgresIntegrationTest {

    private static final UUID CLUB_A = UUID.fromString("019e30c6-2c00-7001-8000-0000000000f1");
    private static final UUID CLUB_B = UUID.fromString("019e30c6-2c00-7001-8000-0000000000f2");

    /** {@code t_start_type} WINCH_LAUNCH / AEROTOW ids (V2 seed, fixed canonical UUIDs). */
    private static final UUID WINCH_LAUNCH =
            UUID.fromString("019e2e15-2c00-7fa0-8000-000000000fa0");
    private static final UUID AEROTOW =
            UUID.fromString("019e2e15-2c00-7fa1-8000-000000000fa1");

    @Autowired JdbcTemplate jdbc;
    @Autowired FlightRepository flights;
    @Autowired FlightReportRowRepository rows;
    @Autowired FlightReportRebuildService rebuild;
    @Autowired AircraftRepository aircraftRepository;
    @Autowired LocationRepository locations;
    @Autowired FlightTypeRepository flightTypes;
    @Autowired PersonRepository persons;

    @BeforeEach
    void cleanAndSeedClubs() {
        new TwoClubFixture(jdbc, CLUB_A, CLUB_B, "IT_FRB_", "IT_FRB").seed();
    }

    @Test
    void rebuild_recreatesDeletedRows_withDecorationsAndTowBlock_andIsIdempotent() {
        UUID gliderAc = seedAircraft(CLUB_A);
        UUID towAc = seedAircraft(CLUB_A);
        UUID loc = seedLocation(CLUB_A, "Rebuildheim");
        UUID gliderType = seedFlightType(CLUB_A, "Strecke", "STR");
        UUID towType = seedFlightType(CLUB_A, "Schlepp", "TOW");
        UUID pilot = seedPerson("Wieder", "Willi");
        UUID towPilot = seedPerson("Schlepp", "Sina");

        UUID towId = seedTow(CLUB_A, towAc, LocalDate.of(2026, 5, 20),
                Instant.parse("2026-05-20T10:00:00Z"), Instant.parse("2026-05-20T10:12:00Z"),
                loc, loc, towType, AEROTOW,
                List.of(crew(towPilot, FlightCrewTypeIds.PILOT_OR_STUDENT)));
        UUID gliderId = seedGlider(CLUB_A, gliderAc, LocalDate.of(2026, 5, 20),
                Instant.parse("2026-05-20T10:00:00Z"), Instant.parse("2026-05-20T13:00:00Z"),
                loc, loc, gliderType, AEROTOW,
                List.of(crew(pilot, FlightCrewTypeIds.PILOT_OR_STUDENT)));
        TenantTestContext.runAs(CLUB_A, () -> {
            Flight glider = flights.findByIdWithCrew(FlightId.of(gliderId)).orElseThrow();
            Flight tow = flights.findByIdWithCrew(FlightId.of(towId)).orElseThrow();
            glider.linkTow(tow);
            flights.save(glider);
        });

        // Simulate the bypass: the rows vanish (as if the flights had arrived
        // without ever passing repository.save).
        TenantTestContext.runAs(CLUB_A, () -> {
            rows.delete(rows.findByFlightId(gliderId).orElseThrow());
            rows.delete(rows.findByFlightId(towId).orElseThrow());
            assertThat(rows.findByFlightId(gliderId)).isEmpty();
            assertThat(rows.findByFlightId(towId)).isEmpty();
        });

        FlightReportRebuildService.RebuildResult first = rebuild.rebuildForClub(CLUB_A);
        assertThat(first.liveFlights()).isEqualTo(2);
        assertThat(first.orphanRowsDeleted()).isZero();

        FlightReportRow gliderRow = rowOf(CLUB_A, gliderId);
        assertThat(gliderRow.getPilotName()).isEqualTo("Wieder Willi");
        assertThat(gliderRow.getStartLocationName()).isEqualTo("Rebuildheim");
        assertThat(gliderRow.getFlightCode()).isEqualTo("STR");
        assertThat(gliderRow.getDurationSeconds()).isEqualTo(10800L);
        assertThat(gliderRow.getCrew()).hasSize(1);
        // Tow block restored from the live link, both directions.
        assertThat(gliderRow.getTowFlightId()).isEqualTo(towId);
        assertThat(gliderRow.getTowPilotName()).isEqualTo("Schlepp Sina");
        assertThat(gliderRow.getTowFlightCode()).isEqualTo("TOW");
        assertThat(rowOf(CLUB_A, towId).getTowedGliderFlightId()).isEqualTo(gliderId);

        // Idempotent: a second run repairs nothing and changes nothing.
        FlightReportRebuildService.RebuildResult second = rebuild.rebuildForClub(CLUB_A);
        assertThat(second.liveFlights()).isEqualTo(2);
        assertThat(second.orphanRowsDeleted()).isZero();
        FlightReportRow gliderRowAfter = rowOf(CLUB_A, gliderId);
        assertThat(gliderRowAfter.getPilotName()).isEqualTo("Wieder Willi");
        assertThat(gliderRowAfter.getTowFlightId()).isEqualTo(towId);
        assertThat(gliderRowAfter.getCrew()).hasSize(1);
        assertThat(countRowsFor(gliderId)).isEqualTo(1);
        assertThat(countRowsFor(towId)).isEqualTo(1);
    }

    @Test
    void rebuild_deletesOrphanedRows_whoseFlightWasSoftDeletedBehindTheProjectorsBack() {
        UUID aircraft = seedAircraft(CLUB_A);
        UUID loc = seedLocation(CLUB_A, "Base");
        UUID flightType = seedFlightType(CLUB_A, "T", "T");
        UUID flightId = seedGlider(CLUB_A, aircraft, LocalDate.of(2026, 5, 15),
                Instant.parse("2026-05-15T08:00:00Z"), Instant.parse("2026-05-15T09:00:00Z"),
                loc, loc, flightType, WINCH_LAUNCH, List.of());
        assertThat(countRowsFor(flightId)).isEqualTo(1);

        // Simulate the bypass under repair: the flight dies WITHOUT the
        // projector noticing (raw JDBC, not the production soft-delete path —
        // that one projects synchronously and is covered by the RM-1 ITs).
        jdbc.update("UPDATE t_flight SET deleted_on = now() WHERE id = ?::uuid",
                flightId.toString());

        FlightReportRebuildService.RebuildResult result = rebuild.rebuildForClub(CLUB_A);
        assertThat(result.orphanRowsDeleted()).isEqualTo(1);
        assertThat(countRowsFor(flightId)).isZero();
    }

    @Test
    void rebuild_isPerClub_andDoesNotTouchTheOtherTenantsRows() {
        UUID acA = seedAircraft(CLUB_A);
        UUID locA = seedLocation(CLUB_A, "BaseA");
        UUID typeA = seedFlightType(CLUB_A, "TA", "TA");
        UUID flightA = seedGlider(CLUB_A, acA, LocalDate.of(2026, 5, 15),
                Instant.parse("2026-05-15T08:00:00Z"), Instant.parse("2026-05-15T09:00:00Z"),
                locA, locA, typeA, WINCH_LAUNCH, List.of());

        UUID acB = seedAircraft(CLUB_B);
        UUID locB = seedLocation(CLUB_B, "BaseB");
        UUID typeB = seedFlightType(CLUB_B, "TB", "TB");
        UUID flightB = seedGlider(CLUB_B, acB, LocalDate.of(2026, 5, 16),
                Instant.parse("2026-05-16T08:00:00Z"), Instant.parse("2026-05-16T09:00:00Z"),
                locB, locB, typeB, WINCH_LAUNCH, List.of());

        // Both clubs lose their rows to the simulated bypass.
        TenantTestContext.runAs(CLUB_A, () ->
                rows.delete(rows.findByFlightId(flightA).orElseThrow()));
        TenantTestContext.runAs(CLUB_B, () ->
                rows.delete(rows.findByFlightId(flightB).orElseThrow()));

        // Rebuilding club A restores ONLY club A.
        rebuild.rebuildForClub(CLUB_A);
        assertThat(countRowsFor(flightA)).isEqualTo(1);
        assertThat(countRowsFor(flightB)).isZero();

        rebuild.rebuildForClub(CLUB_B);
        assertThat(countRowsFor(flightB)).isEqualTo(1);
        // The restored rows carry their own club's discriminator (read-only
        // JDBC assert of the stamped column — not a seeding write).
        assertThat(jdbc.queryForObject(
                "SELECT operating_club_id FROM t_flight_report_row WHERE flight_id = ?::uuid",
                UUID.class, flightB.toString())).isEqualTo(CLUB_B);
    }

    // ---------------------------------------------------------------- helpers
    //
    // Seeding goes through production code — domain factories + repositories
    // under TenantTestContext.runAs (ADR 0027 §3); read-only JDBC only for
    // reference-data id lookups + row-count asserts; raw JDBC writes only to
    // SIMULATE the bypass under test (see class javadoc).

    private FlightReportRow rowOf(UUID clubId, UUID flightId) {
        return TenantTestContext.runAs(clubId, () -> rows.findByFlightId(flightId)
                .orElseThrow(() -> new AssertionError("no report row for flight " + flightId)));
    }

    private int countRowsFor(UUID flightId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM t_flight_report_row WHERE flight_id = ?::uuid",
                Integer.class, flightId.toString());
        return n == null ? 0 : n;
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
        FlightOperationalData ops = new FlightOperationalData(
                date, start, ldg, null, null,
                startLocation, ldgLocation, null, null, null, null,
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
