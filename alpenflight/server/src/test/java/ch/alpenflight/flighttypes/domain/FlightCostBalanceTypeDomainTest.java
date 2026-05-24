package ch.alpenflight.flighttypes.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class FlightCostBalanceTypeDomainTest {

    @Test
    void updateFlags_allFalse_throwsInvariantException() {
        // Replaces V3's dropped ck_fcbt_at_least_one_flag CHECK — see
        // ADR 0022 directive 2 + V3 line 195 comment.
        FlightCostBalanceType fcb = new FlightCostBalanceType();
        assertThatThrownBy(() -> fcb.updateFlags(false, false, false))
                .isInstanceOf(FlightCostBalanceTypeInvariantException.class)
                .hasMessageContaining("at least one");
    }

    @Test
    void updateFlags_anySingleFlag_accepts() {
        FlightCostBalanceType fcb = new FlightCostBalanceType();
        fcb.updateFlags(true, false, false);
        assertThat(fcb.isForGlider()).isTrue();
        fcb.updateFlags(false, true, false);
        assertThat(fcb.isForTow()).isTrue();
        fcb.updateFlags(false, false, true);
        assertThat(fcb.isForMotor()).isTrue();
    }
}
