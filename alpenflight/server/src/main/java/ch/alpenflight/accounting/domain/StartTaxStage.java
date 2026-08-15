package ch.alpenflight.accounting.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public final class StartTaxStage {

    private final AccountingRuleMatcher matcher;

    public StartTaxStage(AccountingRuleMatcher matcher) {
        this.matcher = matcher;
    }

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
