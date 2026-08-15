package ch.alpenflight.flights.web;

import static ch.alpenflight.flights.web.FlightsTestFixtures.cleanFlightRowsFor;
import static ch.alpenflight.flights.web.FlightsTestFixtures.seedAircraftFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.flights.application.FlightStateTransitionService;
import ch.alpenflight.flights.domain.FlightGateNotReachedException;
import ch.alpenflight.flights.domain.FlightProcessState;
import ch.alpenflight.flights.domain.TransitionTrigger;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.platform.id.FlightId;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(FlightTimeGateIT.FixedClockConfig.class)
class FlightTimeGateIT extends PostgresIntegrationTest {

    private static final LocalDate FIXED_TODAY = LocalDate.of(2026, 1, 1);
    private static final Instant FIXED_NOW =
            FIXED_TODAY.atStartOfDay(ZoneOffset.UTC).plusHours(12).toInstant();
    private static final int LOCK_GATE_DAYS_BEFORE_TODAY = 2;

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired FlightStateTransitionService stateService;
    @Autowired ClubRepository clubs;
    @Autowired CountryRepository countries;
    @Autowired ClubStateRepository clubStates;

    private UUID club;
    private UUID other;
    private UUID aircraftId;

    @BeforeEach
    void setUp() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, "tgateit", "TGIT");
        fixture.seed();
        club = fixture.clubA();
        other = fixture.clubB();
        cleanFlightRowsFor(jdbc, club, other);
        aircraftId = seedAircraftFor(jdbc, club);
    }

    @Test
    void flight_one_day_short_of_the_lock_gate_cannot_lock() {
        FlightId id = seedValidFlight(FIXED_TODAY.minusDays(LOCK_GATE_DAYS_BEFORE_TODAY - 1));
        TenantTestContext.runAs(club, () -> {
            assertThatThrownBy(() ->
                    stateService.transition(id, FlightProcessState.LOCKED, TransitionTrigger.LOCK_JOB))
                    .isInstanceOf(FlightGateNotReachedException.class);
            assertThat(processStateOf(id.value())).isEqualTo(FlightProcessState.VALID.id());
            assertThat(lockedAtOf(id.value())).isNull();
        });
    }

    @Test
    void flight_exactly_on_the_lock_gate_boundary_locks_and_stamps_locked_at() {
        FlightId id = seedValidFlight(FIXED_TODAY.minusDays(LOCK_GATE_DAYS_BEFORE_TODAY));
        TenantTestContext.runAs(club, () ->
                stateService.transition(id, FlightProcessState.LOCKED, TransitionTrigger.LOCK_JOB));
        assertThat(processStateOf(id.value())).isEqualTo(FlightProcessState.LOCKED.id());
        Instant lockedAt = lockedAtOf(id.value());
        assertThat(lockedAt)
                .as("locked_at is stamped from the fixed clock")
                .isEqualTo(FIXED_NOW);
    }

    private FlightId seedValidFlight(LocalDate flightDate) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO t_flight (id, operating_club_id, aircraft_id,
                                    flight_aircraft_type_id, flight_date,
                                    is_solo_flight, no_start_time_information,
                                    no_ldg_time_information,
                                    process_state_id, version)
                VALUES (?::uuid, ?::uuid, ?::uuid, 1, ?::date,
                        false, false, false,
                        ?::uuid, 0)
                """,
                id.toString(),
                club.toString(),
                aircraftId.toString(),
                flightDate.toString(),
                FlightProcessState.VALID.id().toString());
        return FlightId.of(id);
    }

    private UUID processStateOf(UUID flightId) {
        return jdbc.queryForObject(
                "SELECT process_state_id FROM t_flight WHERE id = ?::uuid",
                UUID.class, flightId.toString());
    }

    private Instant lockedAtOf(UUID flightId) {
        java.sql.Timestamp ts = jdbc.queryForObject(
                "SELECT locked_at FROM t_flight WHERE id = ?::uuid",
                java.sql.Timestamp.class, flightId.toString());
        return ts == null ? null : ts.toInstant();
    }
}
