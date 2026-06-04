package ch.alpenflight.flights.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.flights.domain.Flight;
import ch.alpenflight.flights.domain.FlightOperationalData;
import ch.alpenflight.flights.domain.FlightProcessState;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Boundary unit test for {@link FlightGatePolicy}. The gate keys on
 * {@code flight_date} (lock) and {@code locked_at} (bill) — a deliberate
 * divergence from legacy's {@code CreatedOn} (J-2 parity decision,
 * operator 2026-06-03). Calendar-day comparison in the server's UTC zone:
 * "now" is anchored with {@link Clock#fixed} so the exact day boundary is
 * pinned.
 */
class FlightGatePolicyTest {

    private static final UUID AIRCRAFT = UUID.fromString("019e2e15-2c00-7af9-8000-0000000000a1");

    /** 2026-01-01T12:00:00Z — the e2e fixture anchor. */
    private static final Instant NOW =
            LocalDate.of(2026, 1, 1).atStartOfDay(ZoneOffset.UTC).plusHours(12).toInstant();
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final FlightGatePolicy policy = new FlightGatePolicy();

    // ---- canLock: flight_date <= today - 2 days ------------------------

    @Test
    void canLock_rejects_one_day_before_the_gate() {
        // today = 2026-01-01, gate = today-2d = 2025-12-30.
        // A flight flown 2025-12-31 (one day too recent) cannot lock.
        Flight f = glider(LocalDate.of(2025, 12, 31));
        assertThat(policy.canLock(f, CLOCK.instant())).isFalse();
    }

    @Test
    void canLock_allows_exactly_on_the_calendar_day_boundary() {
        // flight_date == today-2d (2025-12-30) is allowed (<=).
        Flight f = glider(LocalDate.of(2025, 12, 30));
        assertThat(policy.canLock(f, CLOCK.instant())).isTrue();
    }

    @Test
    void canLock_allows_well_past_the_gate() {
        Flight f = glider(LocalDate.of(2025, 12, 1));
        assertThat(policy.canLock(f, CLOCK.instant())).isTrue();
    }

    // ---- canBill: locked_at <= today - 3 days --------------------------

    @Test
    void canBill_rejects_one_day_before_the_gate() {
        // gate = today-3d = 2025-12-29. Locked 2025-12-30 (one day too
        // recent) cannot bill — even an end-of-day instant stays on the
        // 2025-12-30 calendar day.
        Flight f = locked(LocalDate.of(2025, 12, 30));
        assertThat(policy.canBill(f, CLOCK.instant())).isFalse();
    }

    @Test
    void canBill_allows_exactly_on_the_calendar_day_boundary() {
        // locked_at on 2025-12-29 (today-3d) is allowed (<=), regardless of
        // the time-of-day within that calendar day.
        Flight f = locked(LocalDate.of(2025, 12, 29));
        assertThat(policy.canBill(f, CLOCK.instant())).isTrue();
    }

    @Test
    void canBill_is_false_when_locked_at_is_unset() {
        Flight f = glider(LocalDate.of(2025, 1, 1)); // never locked
        assertThat(policy.canBill(f, CLOCK.instant())).isFalse();
    }

    private static Flight glider(LocalDate flightDate) {
        return Flight.createGlider(AIRCRAFT, FlightProcessState.VALID.id(), ops(flightDate));
    }

    /** A flight in LOCKED state whose {@code locked_at} day is {@code lockedDay}. */
    private static Flight locked(LocalDate lockedDay) {
        Flight f = Flight.createGlider(AIRCRAFT, FlightProcessState.VALID.id(),
                ops(LocalDate.of(2025, 1, 1)));
        // Stamp via the transition path using a clock fixed on the noon of
        // the desired locked day (proves the truncation-to-date).
        Instant at = lockedDay.atStartOfDay(ZoneOffset.UTC).plusHours(12).toInstant();
        f.transition(FlightProcessState.LOCKED,
                ch.alpenflight.flights.domain.TransitionTrigger.LOCK_JOB, at);
        return f;
    }

    private static FlightOperationalData ops(LocalDate flightDate) {
        return new FlightOperationalData(
                flightDate,
                null, null, null, null,
                null, null,
                null, null,
                null, null,
                null, null,
                null, null,
                false, false,
                null, null,
                null, null,
                null,
                null,
                null, null,
                false);
    }
}
