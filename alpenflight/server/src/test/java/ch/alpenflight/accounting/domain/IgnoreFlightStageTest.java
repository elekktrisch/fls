package ch.alpenflight.accounting.domain;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.accounting.domain.MatchableFlight.MatchableCrew;
import ch.alpenflight.flights.domain.FlightAircraftType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IgnoreFlightStageTest {

    private static final AccountingRuleMatcher MATCHER = new AccountingRuleMatcher();
    private static final IgnoreFlightStage STAGE = new IgnoreFlightStage(MATCHER);

    private static final List<MatchableCrew> ONE_PILOT =
            List.of(MatchableCrew.of("PILOT", "1234", null, List.of()));

    private static FilterConfig gliderRule() {
        FilterConfig base = FilterConfig.empty();
        return new FilterConfig(
                true, false, false,
                false, false, false, false, false, false,
                null, null, null, null, null,
                base.aircraftImmatriculations(), base.startTypes(), base.flightTypeCodes(),
                base.startLocations(), base.ldgLocations(), base.clubMemberNumbers(),
                base.flightCrewTypes(), base.aircraftHomebases(), base.memberStates(),
                base.personCategories(),
                null, null);
    }

    private static FilterConfig motorRule() {
        FilterConfig base = FilterConfig.empty();
        return new FilterConfig(
                false, false, true,
                false, false, false, false, false, false,
                null, null, null, null, null,
                base.aircraftImmatriculations(), base.startTypes(), base.flightTypeCodes(),
                base.startLocations(), base.ldgLocations(), base.clubMemberNumbers(),
                base.flightCrewTypes(), base.aircraftHomebases(), base.memberStates(),
                base.personCategories(),
                null, null);
    }

    private static MatchableFlight glider() {
        return MatchableFlight.builder(FlightAircraftType.GLIDER).crew(ONE_PILOT).build();
    }

    @Test
    void anyMatchingDoNotInvoiceFilterSetsTheFlagAndMarksTheFilter() {
        var acc = RuleBasedDeliveryDetails.forClub(UUID.randomUUID());
        UUID nonMatchingId = UUID.randomUUID();
        UUID matchingId = UUID.randomUUID();

        STAGE.run(acc, glider(), List.of(
                RuleFilterInput.of(nonMatchingId, null, motorRule()),
                RuleFilterInput.of(matchingId, null, gliderRule())));

        assertThat(acc.isDoNotInvoiceFlight()).isTrue();
        assertThat(acc.getMatchedFilterIds()).containsExactly(matchingId);
    }

    @Test
    void noMatchingDoNotInvoiceFilterLeavesTheFlagFalse() {
        var acc = RuleBasedDeliveryDetails.forClub(UUID.randomUUID());

        STAGE.run(acc, glider(), List.of(
                RuleFilterInput.of(UUID.randomUUID(), null, motorRule())));

        assertThat(acc.isDoNotInvoiceFlight()).isFalse();
        assertThat(acc.getMatchedFilterIds()).isEmpty();
    }
}
