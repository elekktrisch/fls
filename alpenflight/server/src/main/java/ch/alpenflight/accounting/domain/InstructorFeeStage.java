package ch.alpenflight.accounting.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public final class InstructorFeeStage {

    private static final int NO_INSTRUCTOR_FEE = 4;

    private final AccountingRuleMatcher matcher;

    public InstructorFeeStage(AccountingRuleMatcher matcher) {
        this.matcher = matcher;
    }

    public void run(RuleBasedDeliveryDetails accumulator,
                    MatchableFlight flight,
                    List<RuleFilterInput> instructorFeeFilters) {
        for (RuleFilterInput filter : instructorFeeFilters) {
            if (!matcher.matches(flight, filter.filterConfig())) {
                continue;
            }
            String articleNumber = Objects.requireNonNull(filter.articleNumber(),
                    "InstructorFee filter must carry an articleNumber");
            AccountingUnitType unit = Objects.requireNonNull(filter.accountingUnitType(),
                    "InstructorFee filter must carry an accountingUnitType");
            accumulator.addLineWithoutCoalesce(new DeliveryItemDetails(
                    0,
                    articleNumber,
                    "Fluglehrer-Honorar " + flight.instructorDisplayName(),
                    null,
                    quantity(flight, unit),
                    0,
                    unit.unitTypeString()));
            accumulator.markFilterMatched(filter.filterId());
        }
    }

    private static BigDecimal quantity(MatchableFlight flight, AccountingUnitType unit) {
        if (flight.flightCostBalanceTypeId() == NO_INSTRUCTOR_FEE) {
            return BigDecimal.ZERO;
        }
        return unit.quantityFrom(flight.flightDurationMinutes(), AccountingUnitType.MIN);
    }
}
