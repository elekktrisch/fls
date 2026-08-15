package ch.alpenflight.accounting.domain;

import java.util.List;

public final class VsfFeeStage {

    private final AccountingRuleMatcher matcher;

    public VsfFeeStage(AccountingRuleMatcher matcher) {
        this.matcher = matcher;
    }

    public void run(RuleBasedDeliveryDetails accumulator,
                    MatchableFlight flight,
                    List<RuleFilterInput> vsfFeeFilters) {
        for (RuleFilterInput filter : vsfFeeFilters) {
            if (!matcher.matches(flight, filter.filterConfig())) {
                continue;
            }
            LandingTaxStage.emit(accumulator, filter,
                    LandingTaxStage.quantityOrDefault(flight.nrOfLdgs()));
        }
    }

    public void runOnStartLocation(RuleBasedDeliveryDetails accumulator,
                                   MatchableFlight flight,
                                   List<RuleFilterInput> vsfFeeFilters) {
        if (LandingTaxStage.defaultZero(flight.nrOfLdgsOnStartLocation()) <= 0) {
            return;
        }
        MatchableFlight onStart = flight.withLdgLocationIcao(flight.startLocationIcao());
        for (RuleFilterInput filter : vsfFeeFilters) {
            if (!matcher.matches(onStart, filter.filterConfig())) {
                continue;
            }
            LandingTaxStage.emit(accumulator, filter,
                    LandingTaxStage.quantityOrDefault(flight.nrOfLdgsOnStartLocation()));
        }
    }
}
