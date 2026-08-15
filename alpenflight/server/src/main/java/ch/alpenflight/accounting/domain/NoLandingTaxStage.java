package ch.alpenflight.accounting.domain;

import java.util.List;

public final class NoLandingTaxStage {

    private final AccountingRuleMatcher matcher;

    public NoLandingTaxStage(AccountingRuleMatcher matcher) {
        this.matcher = matcher;
    }

    public void run(RuleBasedDeliveryDetails accumulator,
                    MatchableFlight flight,
                    List<RuleFilterInput> noLandingTaxFilters) {
        for (RuleFilterInput filter : noLandingTaxFilters) {
            if (applies(flight, filter)) {
                FilterConfig config = filter.filterConfig();
                accumulator.setNoLandingTaxForGlider(config.noLandingTaxForGlider());
                accumulator.setNoLandingTaxForTowFlight(config.noLandingTaxForTowingAircraft());
                accumulator.setNoLandingTaxForFlight(config.noLandingTaxForAircraft());
                accumulator.markFilterMatched(filter.filterId());
            }
        }
    }

    private boolean applies(MatchableFlight flight, RuleFilterInput filter) {
        if (!matcher.matches(flight, filter.filterConfig())) {
            return false;
        }
        return LandingTaxStage.durationInWindow(flight.flightDurationSeconds(), filter.filterConfig());
    }
}
