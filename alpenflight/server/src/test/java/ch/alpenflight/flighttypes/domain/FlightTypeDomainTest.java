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
    void register_minSeatsZero_rejects_in_the_aggregate_not_only_at_the_dto_boundary() {
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
                true, false, false, false, false,
                true, false, false,
                false, false, false,
                null);
        ft.updateFlags(false, true, true, true, true, true, true, true, true, true, true);
        assertThat(ft.isInstructorRequired()).isFalse();
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
    void register_instructorAndObserverBothRequired_rejects() {
        assertThatThrownBy(() -> FlightType.register("Beides", null,
                true, true, false, false, false,
                true, false, false,
                false, false, false,
                null))
                .isInstanceOf(InstructorObserverExclusionException.class)
                .hasMessageContaining("observerPilotOrInstructorRequired");
    }

    @Test
    void updateFlags_instructorAndObserverBothRequired_rejectsWithoutMutating() {
        FlightType ft = FlightType.register("Probe", null,
                true, false, false, false, false,
                true, false, false,
                false, false, false,
                null);
        assertThatThrownBy(() -> ft.updateFlags(
                true, true, true, true, true, true, true, true, true, true, true))
                .isInstanceOf(InstructorObserverExclusionException.class);
        assertThat(ft.isInstructorRequired()).isTrue();
        assertThat(ft.isObserverPilotOrInstructorRequired()).isFalse();
        assertThat(ft.isCheckFlight()).isFalse();
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
        ft.softDelete(null, FIXED_CLOCK);
        assertThat(ft.isDeleted()).isTrue();
    }
}
