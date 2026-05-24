package ch.alpenflight.flighttypes.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class FlightTypeDomainTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-05-24T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void register_minimalGliderFlightType_setsNameAndFlags() {
        FlightType ft = FlightType.register("Schulflug", "S",
                true, false, false, false, false,
                true, false, false,
                true, false, false,
                2);
        assertThat(ft.getFlightTypeName()).isEqualTo("Schulflug");
        assertThat(ft.getFlightCode()).isEqualTo("S");
        assertThat(ft.isInstructorRequired()).isTrue();
        assertThat(ft.isForGliderFlights()).isTrue();
        assertThat(ft.isForTowFlights()).isFalse();
        assertThat(ft.getMinNrOfAircraftSeatsRequired()).isEqualTo(2);
        assertThat(ft.isDeleted()).isFalse();
    }

    @Test
    void register_blankName_rejects() {
        assertThatThrownBy(() -> FlightType.register("   ", null,
                false, false, false, false, false,
                true, false, false,
                false, false, false,
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flightTypeName");
    }

    @Test
    void register_minSeatsZero_rejects() {
        // Legacy treated 0 and null identically; the new stack rejects 0 at
        // both the DTO boundary (FlightTypeCreateRequest.@Min(1)) AND the
        // aggregate (assignMinSeats). This test pins the aggregate-side
        // half so a future DTO refactor can't silently re-introduce the
        // legacy ambiguity.
        assertThatThrownBy(() -> FlightType.register("Foo", null,
                false, false, false, false, false,
                true, false, false,
                false, false, false,
                0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minNrOfAircraftSeatsRequired");
    }

    @Test
    void updateFlags_replacesAllElevenFlagsAtomically() {
        FlightType ft = FlightType.register("Probe", null,
                false, false, false, false, false,
                true, false, false,
                false, false, false,
                null);
        ft.updateFlags(true, true, true, true, true, true, true, true, true, true, true);
        assertThat(ft.isInstructorRequired()).isTrue();
        assertThat(ft.isObserverPilotOrInstructorRequired()).isTrue();
        assertThat(ft.isCheckFlight()).isTrue();
        assertThat(ft.isPassengerFlight()).isTrue();
        assertThat(ft.isSoloFlight()).isTrue();
        assertThat(ft.isForGliderFlights()).isTrue();
        assertThat(ft.isForTowFlights()).isTrue();
        assertThat(ft.isForMotorFlights()).isTrue();
        assertThat(ft.isFlightCostBalanceSelectable()).isTrue();
        assertThat(ft.isCouponNumberRequired()).isTrue();
        assertThat(ft.isForAircraftReservationType()).isTrue();
    }

    @Test
    void softDelete_idempotent() {
        FlightType ft = FlightType.register("Toll", null,
                false, false, false, false, false,
                true, false, false,
                false, false, false,
                null);
        ft.softDelete(null, FIXED_CLOCK);
        assertThat(ft.isDeleted()).isTrue();
        // Second softDelete is a no-op — the deletedOn stamp is preserved.
        ft.softDelete(null, FIXED_CLOCK);
        assertThat(ft.isDeleted()).isTrue();
    }
}
