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

    private static final Instant NOW =
            LocalDate.of(2026, 1, 1).atStartOfDay(ZoneOffset.UTC).plusHours(12).toInstant();
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final FlightGatePolicy policy = new FlightGatePolicy();


    @Test
    void canLock_rejects_one_day_before_the_gate() {
        Flight f = glider(LocalDate.of(2025, 12, 31));
        assertThat(policy.canLock(f, CLOCK.instant())).isFalse();
    }

    @Test
    void canLock_allows_exactly_on_the_calendar_day_boundary() {
        Flight f = glider(LocalDate.of(2025, 12, 30));
        assertThat(policy.canLock(f, CLOCK.instant())).isTrue();
    }

    @Test
    void canLock_allows_well_past_the_gate() {
        Flight f = glider(LocalDate.of(2025, 12, 1));
        assertThat(policy.canLock(f, CLOCK.instant())).isTrue();
    }


    @Test
    void canBill_rejects_one_day_before_the_gate() {
        Flight f = locked(LocalDate.of(2025, 12, 30));
        assertThat(policy.canBill(f, CLOCK.instant())).isFalse();
    }

    @Test
    void canBill_allows_exactly_on_the_calendar_day_boundary() {
        Flight f = locked(LocalDate.of(2025, 12, 29));
        assertThat(policy.canBill(f, CLOCK.instant())).isTrue();
    }

    @Test
    void canBill_is_false_when_locked_at_is_unset() {
        Flight f = glider(LocalDate.of(2025, 1, 1));
        assertThat(policy.canBill(f, CLOCK.instant())).isFalse();
    }

    private static Flight glider(LocalDate flightDate) {
        return Flight.createGlider(AIRCRAFT, FlightProcessState.VALID.id(), ops(flightDate));
    }

    private static Flight locked(LocalDate lockedDay) {
        Flight f = Flight.createGlider(AIRCRAFT, FlightProcessState.VALID.id(),
                ops(LocalDate.of(2025, 1, 1)));
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
