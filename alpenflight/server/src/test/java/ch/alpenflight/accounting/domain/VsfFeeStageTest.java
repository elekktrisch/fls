package ch.alpenflight.accounting.domain;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.accounting.domain.FilterConfig.MatchList;
import ch.alpenflight.accounting.domain.MatchableFlight.MatchableCrew;
import ch.alpenflight.flights.domain.FlightAircraftType;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VsfFeeStageTest {

    private static final AccountingRuleMatcher MATCHER = new AccountingRuleMatcher();
    private static final VsfFeeStage VSF_FEE = new VsfFeeStage(MATCHER);

    private static final List<MatchableCrew> ONE_PILOT =
            List.of(MatchableCrew.of("PILOT", "1234", null, List.of()));

    private static MatchableFlight.Builder glider() {
        return MatchableFlight.builder(FlightAircraftType.GLIDER)
                .immatriculation("HB-1234")
                .flightDurationSeconds(1200)
                .crew(ONE_PILOT);
    }

    private static RuleFilterInput vsfFeeFilter(MatchList ldgLocations) {
        FilterConfig base = FilterConfig.empty();
        FilterConfig config = new FilterConfig(
                true, false, false,
                false, false, false, false, false, false,
                null, null, null, null, null,
                base.aircraftImmatriculations(), base.startTypes(), base.flightTypeCodes(),
                base.startLocations(), ldgLocations, base.clubMemberNumbers(),
                base.flightCrewTypes(), base.aircraftHomebases(), base.memberStates(),
                base.personCategories(),
                "VSF-Beitrag", null);
        return new RuleFilterInput(UUID.randomUUID(), null, "VSF", AccountingUnitType.LDGS, config);
    }

    // VsfFeeOnStartLocation matches the flight's START location (LSZK) against the
    // filter's ldg-location set even though the flight landed elsewhere (LSZF), and
    // bills nrOfLdgsOnStartLocation (3); it is forced off when that count is <= 0.
    @Test
    void onStartLocationBillsStartLocationLandingsAndIsForcedOffWhenNone() {
        RuleFilterInput filter = vsfFeeFilter(new MatchList(false, List.of("LSZK")));

        var billed = RuleBasedDeliveryDetails.forClub(UUID.randomUUID());
        VSF_FEE.runOnStartLocation(billed, glider()
                .startLocationIcao("LSZK")
                .ldgLocationIcao("LSZF")
                .nrOfLdgs(5)
                .nrOfLdgsOnStartLocation(3)
                .build(), List.of(filter));

        assertThat(billed.deliveryItems()).hasSize(1);
        assertThat(billed.deliveryItems().get(0).quantity())
                .isEqualByComparingTo(BigDecimal.valueOf(3));

        var forcedOff = RuleBasedDeliveryDetails.forClub(UUID.randomUUID());
        VSF_FEE.runOnStartLocation(forcedOff, glider()
                .startLocationIcao("LSZK")
                .nrOfLdgsOnStartLocation(0)
                .build(), List.of(filter));

        assertThat(forcedOff.deliveryItems()).isEmpty();
    }
}
