package ch.alpenflight.tenancy.sandbox.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DemoSeatTest {

    private static final Instant LEASED_AT = Instant.parse("2026-08-23T10:00:00Z");
    private static final Duration IDLE_PERIOD = Duration.ofMinutes(30);
    private static final String VISITOR_ADDRESS = "203.0.113.7";

    @Test
    void a_fresh_pool_row_is_free() {
        assertThat(new DemoSeat().isFree()).isTrue();
    }

    @Test
    void a_lease_makes_the_seat_live_until_the_idle_period_ends() {
        DemoSeat seat = leasedSeat();

        assertThat(seat.isFree()).isFalse();
        assertThat(seat.getLeaseState()).isEqualTo(DemoSeat.LeaseState.LEASED);
        assertThat(seat.getLeaseHolderKey()).isEqualTo(VISITOR_ADDRESS);
        assertThat(seat.getLeaseExpiresAt()).isEqualTo(LEASED_AT.plus(IDLE_PERIOD));
        assertThat(seat.holdsALiveLeaseAt(LEASED_AT.plus(IDLE_PERIOD).minusSeconds(1))).isTrue();
        assertThat(seat.holdsALiveLeaseFor(VISITOR_ADDRESS, LEASED_AT)).isTrue();
        assertThat(seat.holdsALiveLeaseFor("198.51.100.4", LEASED_AT)).isFalse();
    }

    @Test
    void a_live_lease_is_not_reclaimable() {
        DemoSeat seat = leasedSeat();

        assertThat(seat.isReclaimableAt(LEASED_AT.plus(IDLE_PERIOD).minusSeconds(1))).isFalse();
    }

    @Test
    void an_idle_lease_becomes_reclaimable_and_keeps_the_leased_state() {
        DemoSeat seat = leasedSeat();
        Instant afterTheIdlePeriod = LEASED_AT.plus(IDLE_PERIOD);

        assertThat(seat.holdsALiveLeaseAt(afterTheIdlePeriod)).isFalse();
        assertThat(seat.isReclaimableAt(afterTheIdlePeriod)).isTrue();
        assertThat(seat.getLeaseState()).isEqualTo(DemoSeat.LeaseState.LEASED);
        assertThat(seat.isFree()).isFalse();
    }

    @Test
    void only_a_free_seat_accepts_a_new_lease() {
        DemoSeat seat = leasedSeat();

        DemoSeatIsNotFreeException refused = assertThrows(DemoSeatIsNotFreeException.class,
                () -> seat.leaseTo("198.51.100.4", LEASED_AT.plusSeconds(1), IDLE_PERIOD));

        assertThat(refused.leaseState()).isEqualTo(DemoSeat.LeaseState.LEASED);
    }

    @Test
    void a_reclaimed_seat_returns_to_the_pool_free_of_its_holder() {
        DemoSeat seat = leasedSeat();

        seat.returnToPool(LEASED_AT.plus(IDLE_PERIOD));

        assertThat(seat.isFree()).isTrue();
        assertThat(seat.getLeaseHolderKey()).isNull();
        assertThat(seat.getLeaseExpiresAt()).isNull();
        assertThat(seat.isReclaimableAt(LEASED_AT.plus(IDLE_PERIOD))).isFalse();
    }

    @Test
    void a_blank_address_never_holds_a_lease() {
        assertThatThrownBy(() -> new DemoSeat().leaseTo("   ", LEASED_AT, IDLE_PERIOD))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_non_positive_idle_period_never_produces_an_already_expired_lease() {
        assertThatThrownBy(() -> new DemoSeat().leaseTo(VISITOR_ADDRESS, LEASED_AT, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static DemoSeat leasedSeat() {
        DemoSeat seat = new DemoSeat();
        seat.leaseTo(VISITOR_ADDRESS, LEASED_AT, IDLE_PERIOD);
        return seat;
    }
}
