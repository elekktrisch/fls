package ch.alpenflight.accounting.domain;

import java.util.List;

public final class IgnoreFlightStage {

    private final AccountingRuleMatcher matcher;

    public IgnoreFlightStage(AccountingRuleMatcher matcher) {
        this.matcher = matcher;
    }

    public void run(RuleBasedDeliveryDetails accumulator,
                    MatchableFlight flight,
                    List<RuleFilterInput> doNotInvoiceFilters) {
        for (RuleFilterInput filter : doNotInvoiceFilters) {
            if (matcher.matches(flight, filter.filterConfig())) {
                accumulator.setDoNotInvoiceFlight(true);
                accumulator.markFilterMatched(filter.filterId());
            }
        }
    }
}
