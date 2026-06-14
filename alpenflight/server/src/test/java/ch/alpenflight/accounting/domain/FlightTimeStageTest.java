package ch.alpenflight.accounting.domain;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.accounting.domain.MatchableFlight.MatchableCrew;
import ch.alpenflight.flights.domain.FlightAircraftType;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class FlightTimeStageTest {

    private static final AccountingRuleMatcher MATCHER = new AccountingRuleMatcher();
    private static final FlightTimeStage STAGE = new FlightTimeStage(MATCHER);

    private static final List<MatchableCrew> ONE_PILOT =
            List.of(MatchableCrew.of("PILOT", "1234", null, List.of()));

    private static MatchableFlight glider() {
        return MatchableFlight.builder(FlightAircraftType.GLIDER)
                .immatriculation("HB-1234")
                .flightTypeName("Schulung")
                .crew(ONE_PILOT)
                .build();
    }

    /** A glider-scoped flight-time filter for one tier, billed in minutes. */
    private static RuleFilterInput tier(
            String article, @Nullable Integer min, @Nullable Integer max) {
        FilterConfig base = FilterConfig.empty();
        FilterConfig config = new FilterConfig(
                true, false, false,
                false, false, false, false, false, false,
                null, min, max, null, null,
                base.aircraftImmatriculations(), base.startTypes(), base.flightTypeCodes(),
                base.startLocations(), base.ldgLocations(), base.clubMemberNumbers(),
                base.flightCrewTypes(), base.aircraftHomebases(), base.memberStates(),
                base.personCategories(),
                "Flugzeit", null);
        return new RuleFilterInput(UUID.randomUUID(), null, article, AccountingUnitType.MIN, config);
    }

    // Two tiers on a 1500s flight: the upper tier (min=600/max=MAX) bills
    // 1500-600=900s, resets active->600; in the SAME pass the lower tier
    // (min=0/max=600) then matches the now-600s remainder and bills all 600s,
    // resetting active->0. Order is the apply order: upper tier first.
    @Test
    void tieredBillingEmitsOneItemPerTierInApplyOrder() {
        var acc = RuleBasedDeliveryDetails.forClub(UUID.randomUUID());

        STAGE.run(acc, glider(), 1500, List.of(
                tier("UPPER", 600, null),
                tier("LOWER", 0, 600)));

        List<DeliveryItemDetails> items = acc.deliveryItems();
        assertThat(items).hasSize(2);

        assertThat(items.get(0).articleNumber()).isEqualTo("UPPER");
        assertThat(items.get(0).quantity()).isEqualByComparingTo(new BigDecimal("15"));
        assertThat(items.get(0).unitType()).isEqualTo("Minuten");

        assertThat(items.get(1).articleNumber()).isEqualTo("LOWER");
        assertThat(items.get(1).quantity()).isEqualByComparingTo(new BigDecimal("10"));

        assertThat(acc.getActiveFlightTimeInSeconds()).isZero();
    }

    @Test
    void zeroFlightTimeEmitsNoItems() {
        var acc = RuleBasedDeliveryDetails.forClub(UUID.randomUUID());

        STAGE.run(acc, glider(), 0, List.of(tier("ANY", 0, null)));

        assertThat(acc.deliveryItems()).isEmpty();
    }

    // Tier gap: the only tier covers (600, 1200]. A 1500s flight's first 300s
    // (active in (1200, 1500]) match no tier, so the loop breaks on the first
    // pass with active=1500 still > 0 and NO item — the legacy silent unbilled
    // remainder (warn-only), reproduced bit-exact.
    @Test
    void tierGapLeavesRemainderSilentlyUnbilled() {
        var acc = RuleBasedDeliveryDetails.forClub(UUID.randomUUID());

        STAGE.run(acc, glider(), 1500, List.of(tier("MID", 600, 1200)));

        assertThat(acc.deliveryItems()).isEmpty();
        assertThat(acc.getActiveFlightTimeInSeconds()).isEqualTo(1500);
    }
}
