package ch.alpenflight.reservations.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AircraftReservationDomainTest {

    private static final UUID CLUB_A = UUID.fromString("019e30c3-2c00-7001-8000-000000000001");
    private static final UUID AIRCRAFT = UUID.fromString("019e30c3-2c00-7002-8000-000000000001");
    private static final UUID OTHER_AIRCRAFT = UUID.fromString("019e30c3-2c00-7002-8000-000000000002");
    private static final UUID PILOT = UUID.fromString("019e30c3-2c00-7003-8000-000000000001");
    private static final UUID LOCATION = UUID.fromString("019e30c3-2c00-7004-8000-000000000001");
    private static final UUID TYPE = UUID.fromString("019e30c3-2c00-7005-8000-000000000001");

    @Test
    void timed_endNotAfterStart_rejected() {
        Instant t = Instant.parse("2026-06-06T10:00:00Z");
        assertThatThrownBy(() -> timed(t, t))
                .isInstanceOf(InvalidReservationDurationException.class);
        assertThatThrownBy(() -> timed(t, t.minusSeconds(1)))
                .isInstanceOf(InvalidReservationDurationException.class);
    }

    @Test
    void conflict_halfOpenOverlap_trueWhenOverlapping_falseWhenAdjacentOrOtherAircraft() {
        AircraftReservation base = timed(
                Instant.parse("2026-06-06T10:00:00Z"),
                Instant.parse("2026-06-06T12:00:00Z"));

        AircraftReservation overlapping = timed(
                Instant.parse("2026-06-06T11:00:00Z"),
                Instant.parse("2026-06-06T13:00:00Z"));
        assertThat(base.conflictsWith(overlapping)).isTrue();

        AircraftReservation adjacent = timed(
                Instant.parse("2026-06-06T12:00:00Z"),
                Instant.parse("2026-06-06T13:00:00Z"));
        assertThat(base.conflictsWith(adjacent)).isFalse();

        AircraftReservation otherAircraft = AircraftReservation.create(
                CLUB_A, OTHER_AIRCRAFT, PILOT, LOCATION, TYPE, null,
                Instant.parse("2026-06-06T11:00:00Z"),
                Instant.parse("2026-06-06T13:00:00Z"),
                false, null, null);
        assertThat(base.conflictsWith(otherAircraft)).isFalse();
    }

    @Test
    void selfExclusion_byId_and_allDayFullDaySpan() {
        AircraftReservation base = timed(
                Instant.parse("2026-06-06T10:00:00Z"),
                Instant.parse("2026-06-06T12:00:00Z"));
        base.assignIdForTest(UUID.fromString("019e30c3-2c00-7006-8000-0000000000aa"));
        assertThat(base.conflictsWith(base)).isFalse();

        AircraftReservation allDay = AircraftReservation.create(
                CLUB_A, AIRCRAFT, PILOT, LOCATION, TYPE, null,
                LocalDate.of(2026, 6, 6).atStartOfDay(ZoneOffset.UTC).toInstant(),
                LocalDate.of(2026, 6, 6).atStartOfDay(ZoneOffset.UTC).toInstant(),
                true, null, null);
        assertThat(allDay.effectiveStart())
                .isEqualTo(Instant.parse("2026-06-06T00:00:00Z"));
        assertThat(allDay.effectiveEnd())
                .isEqualTo(Instant.parse("2026-06-07T00:00:00Z"));

        AircraftReservation lateSlot = timed(
                Instant.parse("2026-06-06T23:00:00Z"),
                Instant.parse("2026-06-06T23:30:00Z"));
        assertThat(allDay.conflictsWith(lateSlot)).isTrue();
    }

    private static AircraftReservation timed(Instant start, Instant end) {
        return AircraftReservation.create(
                CLUB_A, AIRCRAFT, PILOT, LOCATION, TYPE, null,
                start, end, false, null, null);
    }
}
