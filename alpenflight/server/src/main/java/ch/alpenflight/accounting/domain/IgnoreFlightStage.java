package ch.alpenflight.accounting.domain;

import java.util.List;

/**
 * The DoNotInvoice short-circuit stage — the legacy
 * {@code IgnoreFlightRulesEngine} over the active type-5
 * ({@code DoNotInvoiceFlightRuleFilter}) filters. Each filter is evaluated by
 * the {@link AccountingRuleMatcher}; ANY match flips the accumulator's
 * {@code doNotInvoiceFlight} flag and records the matched filter id. The
 * orchestrator (T-12) reads the flag to return zero delivery items — this stage
 * only SETS it.
 *
 * <p>Ported from
 * {@code flsserver/src/FLS.Server.Service/Accounting/RuleEngines/IgnoreFlightRulesEngine.cs}
 * (+ {@code DoNotInvoiceFlightRule.cs}). Unlike the recipient stage, no rule
 * sets {@code StopRuleEngineWhenRuleApplied}, so EVERY type-5 filter is
 * evaluated (no early-out); each match independently sets the flag + marks its
 * filter.
 */
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
