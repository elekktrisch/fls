package ch.alpenflight.accounting.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * The StartTax (type 55) single pass — ported from
 * {@code flsserver/src/FLS.Server.Service/Accounting/Rules/ItemRules/StartTaxRule.cs}.
 * Each matching filter (base conditions AND the duration window) emits a
 * quantity-1 line; a same-article line already present coalesces (legacy
 * {@code Quantity++}) via {@link RuleBasedDeliveryDetails#addItem}.
 *
 * <p>Bit-exact port — NOT rewritten from understanding (customer invoices depend
 * on the current behavior, the J-9 contract).
 */
public final class StartTaxStage {

    private final AccountingRuleMatcher matcher;

    public StartTaxStage(AccountingRuleMatcher matcher) {
        this.matcher = matcher;
    }

    /** Runs over the active type-55 filters in {@code sort_indicator, id} order. */
    public void run(RuleBasedDeliveryDetails accumulator,
                    MatchableFlight flight,
                    List<RuleFilterInput> startTaxFilters) {
        for (RuleFilterInput filter : startTaxFilters) {
            if (!LandingTaxStage.durationInWindow(flight.flightDurationSeconds(), filter.filterConfig())
                    || !matcher.matches(flight, filter.filterConfig())) {
                continue;
            }
            String articleNumber = Objects.requireNonNull(filter.articleNumber(),
                    "StartTax filter must carry an articleNumber");
            AccountingUnitType unit = Objects.requireNonNull(filter.accountingUnitType(),
                    "StartTax filter must carry an accountingUnitType");
            accumulator.addItem(new DeliveryItemDetails(
                    0,
                    articleNumber,
                    Objects.toString(filter.filterConfig().deliveryLineText(), ""),
                    null,
                    BigDecimal.ONE,
                    0,
                    unit.unitTypeString()));
            accumulator.markFilterMatched(filter.filterId());
        }
    }
}
