package ch.alpenflight.accounting.domain;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.accounting.domain.MatchableFlight.MatchableCrew;
import ch.alpenflight.flights.domain.FlightAircraftType;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class EngineTimeStageTest {

    private static final AccountingRuleMatcher MATCHER = new AccountingRuleMatcher();
    private static final EngineTimeStage STAGE = new EngineTimeStage(MATCHER);

    private static final List<MatchableCrew> ONE_PILOT =
            List.of(MatchableCrew.of("PILOT", "1234", null, List.of()));

    private static MatchableFlight motor() {
        return MatchableFlight.builder(FlightAircraftType.MOTOR)
                .immatriculation("HB-1234")
                .flightTypeName("Schulung")
                .crew(ONE_PILOT)
                .build();
    }

    private static RuleFilterInput motorTierBilledInMinutes(
            String article, @Nullable Integer min, @Nullable Integer max) {
        FilterConfig base = FilterConfig.empty();
        FilterConfig config = new FilterConfig(
                false, false, true,
                false, false, false, false, false, false,
                null, null, null, min, max,
                base.aircraftImmatriculations(), base.startTypes(), base.flightTypeCodes(),
                base.startLocations(), base.ldgLocations(), base.clubMemberNumbers(),
                base.flightCrewTypes(), base.aircraftHomebases(), base.memberStates(),
                base.personCategories(),
                "Motorzeit", null);
        return new RuleFilterInput(UUID.randomUUID(), null, article, AccountingUnitType.MIN, config);
    }

    @Test
    void tieredBillingEmitsOneItemPerTierInApplyOrder() {
        var acc = RuleBasedDeliveryDetails.forClub(UUID.randomUUID());

        STAGE.run(acc, motor(), 1500, List.of(
                motorTierBilledInMinutes("UPPER", 600, null),
                motorTierBilledInMinutes("LOWER", 0, 600)));

        List<DeliveryItemDetails> items = acc.deliveryItems();
        assertThat(items).hasSize(2);

        assertThat(items.get(0).articleNumber()).isEqualTo("UPPER");
        assertThat(items.get(0).quantity()).isEqualByComparingTo(new BigDecimal("15"));
        assertThat(items.get(0).unitType()).isEqualTo("Minuten");

        assertThat(items.get(1).articleNumber()).isEqualTo("LOWER");
        assertThat(items.get(1).quantity()).isEqualByComparingTo(new BigDecimal("10"));

        assertThat(acc.getActiveEngineTimeInSeconds()).isZero();
    }

    @Test
    void zeroEngineTimeEmitsNoItems() {
        var acc = RuleBasedDeliveryDetails.forClub(UUID.randomUUID());

        STAGE.run(acc, motor(), 0, List.of(motorTierBilledInMinutes("ANY", 0, null)));

        assertThat(acc.deliveryItems()).isEmpty();
    }

    @Test
    void tierGapLeavesRemainderSilentlyUnbilled() {
        var acc = RuleBasedDeliveryDetails.forClub(UUID.randomUUID());

        STAGE.run(acc, motor(), 1500, List.of(motorTierBilledInMinutes("MID", 600, 1200)));

        assertThat(acc.deliveryItems()).isEmpty();
        assertThat(acc.getActiveEngineTimeInSeconds()).isEqualTo(1500);
    }
}
