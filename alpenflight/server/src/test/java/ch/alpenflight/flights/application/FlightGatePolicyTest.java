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

class FlightGatePolicyTest {

    private static final UUID AIRCRAFT = UUID.fromString("019e2e15-2c00-7af9-8000-0000000000a1");

    private static final LocalDate TODAY = LocalDate.of(2026, 1, 1);
    private static final Clock CLOCK_AT_NOON_TODAY = Clock.fixed(
            TODAY.atStartOfDay(ZoneOffset.UTC).plusHours(12).toInstant(), ZoneOffset.UTC);

    private static final LocalDate NEWEST_LOCKABLE_FLIGHT_DAY = TODAY.minusDays(2);
    private static final LocalDate ONE_DAY_TOO_RECENT_TO_LOCK = TODAY.minusDays(1);
    private static final LocalDate NEWEST_BILLABLE_LOCK_DAY = TODAY.minusDays(3);
    private static final LocalDate ONE_DAY_TOO_RECENT_TO_BILL = TODAY.minusDays(2);

    private final FlightGatePolicy policy = new FlightGatePolicy();


    @Test
    void canLock_rejects_a_flight_flown_one_day_too_recently() {
        Flight f = glider(ONE_DAY_TOO_RECENT_TO_LOCK);
        assertThat(policy.canLock(f, CLOCK_AT_NOON_TODAY.instant())).isFalse();
    }

    @Test
    void canLock_allows_a_flight_flown_exactly_on_the_gate_day() {
        Flight f = glider(NEWEST_LOCKABLE_FLIGHT_DAY);
        assertThat(policy.canLock(f, CLOCK_AT_NOON_TODAY.instant())).isTrue();
    }

    @Test
    void canLock_allows_well_past_the_gate() {
        Flight f = glider(TODAY.minusMonths(1));
        assertThat(policy.canLock(f, CLOCK_AT_NOON_TODAY.instant())).isTrue();
    }


    @Test
    void canBill_rejects_a_flight_locked_one_day_too_recently() {
        Flight f = lockedAtNoonOf(ONE_DAY_TOO_RECENT_TO_BILL);
        assertThat(policy.canBill(f, CLOCK_AT_NOON_TODAY.instant())).isFalse();
    }

    @Test
    void canBill_allows_a_flight_locked_at_any_time_on_the_gate_day() {
        Flight f = lockedAtNoonOf(NEWEST_BILLABLE_LOCK_DAY);
        assertThat(policy.canBill(f, CLOCK_AT_NOON_TODAY.instant())).isTrue();
    }

    @Test
    void canBill_is_false_when_locked_at_is_unset() {
        Flight f = glider(TODAY.minusYears(1));
        assertThat(policy.canBill(f, CLOCK_AT_NOON_TODAY.instant())).isFalse();
    }

    private static Flight glider(LocalDate flightDate) {
        return Flight.createGlider(AIRCRAFT, FlightProcessState.VALID.id(), ops(flightDate));
    }

    private static Flight lockedAtNoonOf(LocalDate lockedDay) {
        Flight f = Flight.createGlider(AIRCRAFT, FlightProcessState.VALID.id(),
                ops(TODAY.minusYears(1)));
        Instant noonOfLockedDay = lockedDay.atStartOfDay(ZoneOffset.UTC).plusHours(12).toInstant();
        f.transition(FlightProcessState.LOCKED,
                ch.alpenflight.flights.domain.TransitionTrigger.LOCK_JOB, noonOfLockedDay);
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
