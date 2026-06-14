package ch.alpenflight.accounting.domain;

import java.util.List;
import java.util.Objects;

/**
 * The AdditionalFuelFee (type 50) single pass — ported from
 * {@code flsserver/src/FLS.Server.Service/Accounting/Rules/ItemRules/AdditionalFuelFeeRule.cs}.
 * Each matching filter (base conditions only; no duration window) emits a line
 * whose quantity is the flight duration in minutes; a same-article line already
 * present coalesces (legacy multi-rule-match quantity-add) via
 * {@link RuleBasedDeliveryDetails#addItem}.
 *
 * <p>Bit-exact port — NOT rewritten from understanding (customer invoices depend
 * on the current behavior, the J-9 contract).
 */
public final class AdditionalFuelFeeStage {

    private final AccountingRuleMatcher matcher;

    public AdditionalFuelFeeStage(AccountingRuleMatcher matcher) {
        this.matcher = matcher;
    }

    /** Runs over the active type-50 filters in {@code sort_indicator, id} order. */
    public void run(RuleBasedDeliveryDetails accumulator,
                    MatchableFlight flight,
                    List<RuleFilterInput> additionalFuelFeeFilters) {
        for (RuleFilterInput filter : additionalFuelFeeFilters) {
            if (!matcher.matches(flight, filter.filterConfig())) {
                continue;
            }
            String articleNumber = Objects.requireNonNull(filter.articleNumber(),
                    "AdditionalFuelFee filter must carry an articleNumber");
            AccountingUnitType unit = Objects.requireNonNull(filter.accountingUnitType(),
                    "AdditionalFuelFee filter must carry an accountingUnitType");
            String itemText = Objects.toString(filter.filterConfig().deliveryLineText(), "")
                    + " " + flight.immatriculation();
            accumulator.addItem(new DeliveryItemDetails(
                    0,
                    articleNumber,
                    itemText,
                    null,
                    unit.quantityFrom(flight.flightDurationMinutes(), AccountingUnitType.MIN),
                    0,
                    unit.unitTypeString()));
            accumulator.markFilterMatched(filter.filterId());
        }
    }
}
