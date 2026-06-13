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

/**
 * Integration proof for the S-061 time-gate wired into
 * {@link FlightStateTransitionService}. A {@link Clock#fixed} bean anchors
 * "now" to 2026-01-01 (the e2e fixture anchor) so the calendar gate is
 * deterministic.
 *
 * <p>The gate is a DELIBERATE divergence from legacy (J-2 parity decision,
 * operator 2026-06-03): lock on {@code flight_date <= today-2d}, bill on
 * {@code locked_at <= today-3d}. The HTTP {@code PATCH /process-state}
 * surface only exposes the OPERATOR trigger (which cannot drive
 * Valid→Locked or Locked→DeliveryPrepared per the matrix), so this IT
 * drives the service directly with the system triggers — the same path the
 * lock / delivery-prep jobs (J-15 / J-10) will use.
 */
@Import(FlightTimeGateIT.FixedClockConfig.class)
class FlightTimeGateIT extends PostgresIntegrationTest {

    /** Anchors "now" to 2026-01-01T12:00Z. */
    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(
                    LocalDate.of(2026, 1, 1).atStartOfDay(ZoneOffset.UTC).plusHours(12).toInstant(),
                    ZoneOffset.UTC);
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
    void too_recent_flight_cannot_lock() {
        // today = 2026-01-01, gate = today-2d = 2025-12-30. A flight flown
        // 2025-12-31 (one day too recent) is rejected at the lock gate.
        // The minted club id is runtime, so the tenant context is set via
        // runAs here rather than the class-level @WithTenant literal.
        FlightId id = seedValidFlight(LocalDate.of(2025, 12, 31));
        TenantTestContext.runAs(club, () -> {
            assertThatThrownBy(() ->
                    stateService.transition(id, FlightProcessState.LOCKED, TransitionTrigger.LOCK_JOB))
                    .isInstanceOf(FlightGateNotReachedException.class);
            // State unchanged in the DB.
            assertThat(processStateOf(id.value())).isEqualTo(FlightProcessState.VALID.id());
            assertThat(lockedAtOf(id.value())).isNull();
        });
    }

    @Test
    void past_threshold_flight_locks_and_stamps_locked_at() {
        // flight_date == today-2d (2025-12-30) is on the boundary → allowed.
        FlightId id = seedValidFlight(LocalDate.of(2025, 12, 30));
        TenantTestContext.runAs(club, () ->
                stateService.transition(id, FlightProcessState.LOCKED, TransitionTrigger.LOCK_JOB));
        assertThat(processStateOf(id.value())).isEqualTo(FlightProcessState.LOCKED.id());
        // locked_at stamped from the fixed clock (2026-01-01T12:00Z).
        Instant lockedAt = lockedAtOf(id.value());
        assertThat(lockedAt).isEqualTo(Instant.parse("2026-01-01T12:00:00Z"));
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
