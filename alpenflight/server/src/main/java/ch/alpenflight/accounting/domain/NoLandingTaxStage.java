package ch.alpenflight.accounting.domain;

import java.util.List;

/**
 * The NoLandingTax (type 20) single pass — ported from
 * {@code flsserver/src/FLS.Server.Service/Accounting/Rules/ItemRules/NoLandingTaxRule.cs}.
 * It emits NO delivery line; a matching filter only sets the three suppression
 * flags on the accumulator that {@link LandingTaxStage} reads to force its lines
 * off. It therefore MUST run before LandingTax (the orchestrator, T-12, enforces
 * the order).
 *
 * <p>Bit-exact port — NOT rewritten from understanding (customer invoices depend
 * on the current behavior, the J-9 contract).
 */
public final class NoLandingTaxStage {

    private final AccountingRuleMatcher matcher;

    public NoLandingTaxStage(AccountingRuleMatcher matcher) {
        this.matcher = matcher;
    }

    /**
     * Runs over the active type-20 filters in {@code sort_indicator, id} order;
     * each filter that matches (base conditions AND the duration window) sets the
     * accumulator's no-landing-tax flags from the filter's own
     * {@code noLandingTaxForGlider / ForTowingAircraft / ForAircraft} flags.
     */
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
