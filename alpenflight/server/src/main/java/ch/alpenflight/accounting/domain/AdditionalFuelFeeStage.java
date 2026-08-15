package ch.alpenflight.accounting.domain;

import java.util.List;
import java.util.Objects;

public final class AdditionalFuelFeeStage {

    private final AccountingRuleMatcher matcher;

    public AdditionalFuelFeeStage(AccountingRuleMatcher matcher) {
        this.matcher = matcher;
    }

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
