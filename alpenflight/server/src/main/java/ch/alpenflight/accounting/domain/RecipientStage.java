package ch.alpenflight.accounting.domain;

import ch.alpenflight.accounting.domain.RuleBasedDeliveryDetails.Recipient;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The recipient-resolution stage — the legacy {@code RecipientRulesEngine} over
 * the active type-10 ({@code RecipientAccountingRuleFilter}) filters, followed
 * by the two appended {@code FlightCostPaidBy*} fallback rules.
 *
 * <p>Ported from
 * {@code flsserver/src/FLS.Server.Service/Accounting/RuleEngines/RecipientRulesEngine.cs}
 * (+ {@code DeliveryRecipientRule.cs} / {@code FlightCostPaidByPersonRule.cs} /
 * {@code FlightCostPaidByPilotRule.cs}). The legacy engine ran a combined rule
 * list through {@code ApplyRules}, where each recipient rule carried
 * {@code StopRuleEngineWhenRuleApplied = true} and the two fallbacks did not;
 * the loop applied a rule when valid and broke after the first applied-and-stop
 * rule. That yields:
 *
 * <ul>
 *   <li><b>First-match-wins</b> among recipient filters: the first matching
 *       filter (in {@code sort_indicator, id} order) sets the recipient and
 *       stops, so the fallbacks never run.</li>
 *   <li><b>Fallbacks only when no recipient filter matched</b>, and since
 *       neither fallback stops, the <b>last applying</b> one wins. Their
 *       conditions are mutually exclusive (distinct cost-balance ints), so in
 *       practice at most one applies.</li>
 * </ul>
 */
public final class RecipientStage {

    private static final int COSTS_PAID_BY_PERSON = 5;
    private static final int PILOT_PAYS_ALL_COSTS = 1;
    private static final int NO_INSTRUCTOR_FEE = 4;

    private final AccountingRuleMatcher matcher;

    public RecipientStage(AccountingRuleMatcher matcher) {
        this.matcher = matcher;
    }

    public void run(RuleBasedDeliveryDetails accumulator,
                    MatchableFlight flight,
                    List<RuleFilterInput> recipientFilters) {
        for (RuleFilterInput filter : recipientFilters) {
            if (matcher.matches(flight, filter.filterConfig())) {
                applyRecipientFilter(accumulator, filter);
                return;
            }
        }
        applyFallbacks(accumulator, flight);
    }

    // DeliveryRecipientRule throws when a matched recipient filter has no
    // RecipientTarget — legacy cannot create a delivery without a recipient.
    private void applyRecipientFilter(RuleBasedDeliveryDetails accumulator, RuleFilterInput filter) {
        Recipient target = filter.recipientTarget();
        if (target == null) {
            throw new IllegalArgumentException(
                    "Recipient target is null. Can not create delivery for matched recipient filter "
                            + filter.filterId());
        }
        accumulator.setRecipient(target);
        accumulator.markFilterMatched(filter.filterId());
    }

    // Appended fallbacks: FlightCostPaidByPerson then FlightCostPaidByPilot.
    // Neither stops, so the last applying wins; they set no matched-filter id
    // (no filter row stands behind them).
    private void applyFallbacks(RuleBasedDeliveryDetails accumulator, MatchableFlight flight) {
        int balanceTypeId = flight.flightCostBalanceTypeId();

        if (balanceTypeId == COSTS_PAID_BY_PERSON) {
            setIfPresent(accumulator, flight.flightCostInvoiceRecipient());
        }
        if (balanceTypeId == PILOT_PAYS_ALL_COSTS || balanceTypeId == NO_INSTRUCTOR_FEE) {
            setIfPresent(accumulator, flight.pilot());
        }
    }

    // Legacy resolves the recipient via the person service and only assigns when
    // the person is found; an unresolved candidate leaves the recipient untouched.
    private void setIfPresent(RuleBasedDeliveryDetails accumulator, @Nullable Recipient recipient) {
        if (recipient != null) {
            accumulator.setRecipient(recipient);
        }
    }
}
