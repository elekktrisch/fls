package ch.alpenflight.flights.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.aircraft.domain.Aircraft;
import ch.alpenflight.aircraft.domain.AircraftRepository;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.flights.application.DailyFlightValidationJob.RunSummary;
import ch.alpenflight.flights.domain.CrewMemberSpec;
import ch.alpenflight.flights.domain.Flight;
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
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 * Integration proof of the daily validation + lock job (S-083, J-15 AC #3): a
 * cross-tenant "Run now" validates every unprocessed flight and locks the
 * gate-eligible valid ones, in both clubs, without touching flights that must
 * not move.
 *
 * <p>The negative seeds carry the weight — a flight one day inside the lock gate
 * and a flight that cannot validate are asserted to STAY where they are, so a job
 * that indiscriminately locked or validated everything would red here.
 */
class DailyFlightValidationJobIT extends PostgresIntegrationTest {

    /** {@code t_start_type} WINCH_LAUNCH id (V2 seed, fixed canonical UUID). */
    private static final UUID WINCH_LAUNCH =
            UUID.fromString("019e2e15-2c00-7fa0-8000-000000000fa0");

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    /** Past the {@link FlightGatePolicy} lock gate ({@code flight_date <= today - 2}). */
    private static final LocalDate LOCKABLE_DAY = TODAY.minusDays(5);

    /** One day short of the lock gate — the narrowing assertion's negative seed. */
    private static final LocalDate TOO_RECENT_DAY = TODAY.minusDays(1);

    @Autowired JdbcTemplate jdbc;
    @Autowired DailyFlightValidationJob job;
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
    private final Map<UUID, ClubSeed> clubSeeds = new HashMap<>();

    @BeforeEach
    void cleanAndSeedClubs() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, "IT_DFV_", "IT_DFV");
        fixture.seed();
        clubA = fixture.clubA();
        clubB = fixture.clubB();
        clubSeeds.clear();
    }

    @Test
    void runOnce_validatesUnprocessedAndLocksGateEligible_acrossClubs() {
        UUID unprocessed = seedFlight(clubA, FlightProcessState.NOT_PROCESSED, TOO_RECENT_DAY, true);
        UUID lockable = seedFlight(clubA, FlightProcessState.VALID, LOCKABLE_DAY, true);
        UUID incomplete = seedFlight(clubA, FlightProcessState.NOT_PROCESSED, TOO_RECENT_DAY, false);
        UUID tooRecent = seedFlight(clubA, FlightProcessState.VALID, TOO_RECENT_DAY, true);
        UUID otherClub = seedFlight(clubB, FlightProcessState.NOT_PROCESSED, TOO_RECENT_DAY, true);

        RunSummary summary = job.runOnce();

        // A complete unprocessed flight validates; it stays VALID because its
        // flying day is inside the lock gate.
        assertThat(stateOf(clubA, unprocessed)).isEqualTo(FlightProcessState.VALID);
        // A valid flight past the gate locks.
        assertThat(stateOf(clubA, lockable)).isEqualTo(FlightProcessState.LOCKED);
        // A flight the validator rejects lands INVALID, not VALID.
        assertThat(stateOf(clubA, incomplete)).isEqualTo(FlightProcessState.INVALID);
        // The gate narrows: one day short of it, a valid flight is left alone.
        assertThat(stateOf(clubA, tooRecent)).isEqualTo(FlightProcessState.VALID);
        // The run is cross-tenant — the second club's flight is processed too.
        assertThat(stateOf(clubB, otherClub)).isEqualTo(FlightProcessState.VALID);

        assertThat(summary.validatedCount()).isGreaterThanOrEqualTo(2);
        assertThat(summary.invalidatedCount()).isGreaterThanOrEqualTo(1);
        assertThat(summary.lockedCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void validationStampsOutcomeOnTheFlight() {
        UUID incomplete = seedFlight(clubA, FlightProcessState.NOT_PROCESSED, TOO_RECENT_DAY, false);
        UUID complete = seedFlight(clubA, FlightProcessState.NOT_PROCESSED, TOO_RECENT_DAY, true);

        job.runOnce();

        Flight rejected = flightOf(clubA, incomplete);
        assertThat(rejected.getValidatedOn()).isNotNull();
        assertThat(rejected.getValidationErrors()).contains("VALIDATION_ERROR_No_pilot_set");

        Flight accepted = flightOf(clubA, complete);
        assertThat(accepted.getValidatedOn()).isNotNull();
        assertThat(accepted.getValidationErrors()).isEmpty();
    }

    @Test
    void repeatedRunIsIdempotent_stillInvalidFlightStaysInvalid() {
        UUID incomplete = seedFlight(clubA, FlightProcessState.NOT_PROCESSED, TOO_RECENT_DAY, false);

        job.runOnce();
        Instant firstPass = flightOf(clubA, incomplete).getValidatedOn();
        job.runOnce();

        assertThat(stateOf(clubA, incomplete)).isEqualTo(FlightProcessState.INVALID);
        assertThat(flightOf(clubA, incomplete).getValidatedOn()).isAfterOrEqualTo(firstPass);
    }

    // ---------------------------------------------------------------- helpers
    //
    // Seeding goes through production code — domain factories + repositories
    // under TenantTestContext.runAs (ADR 0027 §3); read-only JDBC only for
    // reference-data id lookups.

    private FlightProcessState stateOf(UUID clubId, UUID flightId) {
        return flightOf(clubId, flightId).getProcessState();
    }

    private Flight flightOf(UUID clubId, UUID flightId) {
        return TenantTestContext.runAs(clubId, () -> flights.findByIdWithCrew(FlightId.of(flightId))
                .orElseThrow(() -> new AssertionError("no flight " + flightId)));
    }

    /**
     * A glider flight in the given state. {@code withPilot} false omits the crew,
     * which is what makes the flight fail validation.
     */
    private UUID seedFlight(UUID clubId, FlightProcessState state, LocalDate date,
                            boolean withPilot) {
        ClubSeed seed = seedFor(clubId);
        // A winch-launched glider needs a winch operator as well as a pilot, else
        // the validator rejects it — the complete seed carries both.
        List<CrewMemberSpec> crew = withPilot
                ? List.of(crew(seedPerson(), FlightCrewTypeIds.PILOT_OR_STUDENT),
                          crew(seedPerson(), FlightCrewTypeIds.WINCH_OPERATOR))
                : List.of();
        FlightOperationalData ops = ops(date, seed.location(), seed.flightType());
        return TenantTestContext.runAs(clubId, () -> {
            Flight flight = Flight.createGlider(seed.aircraft(), state.id(), ops);
            flight.replaceCrew(crew);
            return flights.save(flight).getId();
        });
    }

    /** The club's aircraft / location / flight-type, created once per test. */
    private ClubSeed seedFor(UUID clubId) {
        return clubSeeds.computeIfAbsent(clubId, id ->
                new ClubSeed(seedAircraft(id), seedLocation(id, "Base"), seedFlightType(id)));
    }

    private record ClubSeed(UUID aircraft, UUID location, UUID flightType) {}

    private static CrewMemberSpec crew(UUID personId, UUID crewTypeId) {
        return new CrewMemberSpec(personId, crewTypeId, null, null, null, null, null, null);
    }

    private static FlightOperationalData ops(LocalDate date, UUID location, UUID flightType) {
        Instant start = date.atTime(9, 0).toInstant(ZoneOffset.UTC);
        Instant ldg = date.atTime(10, 0).toInstant(ZoneOffset.UTC);
        return new FlightOperationalData(
                date, start, ldg, null, null,
                location, location, null, null, null, null,
                flightType, WINCH_LAUNCH, (short) 1, (short) 0,
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
        return TenantTestContext.runAs(clubId, () -> locations.save(location).getId().value());
    }

    private UUID seedFlightType(UUID clubId) {
        FlightType flightType = FlightType.register("Schul", "SCH",
                false, false, false, false, false,
                true, true, true,
                false, false, false, null);
        return TenantTestContext.runAs(clubId, () -> flightTypes.save(flightType).getId().value());
    }

    private UUID seedPerson() {
        return persons.save(Person.register("Anna", "Tester", null)).getId().value();
    }
}
