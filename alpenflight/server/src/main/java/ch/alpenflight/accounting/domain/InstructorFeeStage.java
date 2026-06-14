package ch.alpenflight.accounting.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * The InstructorFee (type 40) single pass — ported from
 * {@code flsserver/src/FLS.Server.Service/Accounting/Rules/ItemRules/InstructorFeeRule.cs}.
 * Each matching filter (base conditions only; no duration window) ALWAYS emits a
 * brand-new line via {@link RuleBasedDeliveryDetails#addLineWithoutCoalesce} — the
 * legacy rule never folds into an existing same-article line the way the other
 * single-pass fee rules do, so two matching instructor-fee filters with the same
 * article yield two distinct lines.
 *
 * <p>Bit-exact port — NOT rewritten from understanding (customer invoices depend
 * on the current behavior, the J-9 contract).
 */
public final class InstructorFeeStage {

    private static final int NO_INSTRUCTOR_FEE = 4;

    private final AccountingRuleMatcher matcher;

    public InstructorFeeStage(AccountingRuleMatcher matcher) {
        this.matcher = matcher;
    }

    /** Runs over the active type-40 filters in {@code sort_indicator, id} order. */
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

    // A NoInstructorFee flight bills zero (the duration is still computed for
    // every other flight) — a non-derivable quirk preserved bit-exact.
    private static BigDecimal quantity(MatchableFlight flight, AccountingUnitType unit) {
        if (flight.flightCostBalanceTypeId() == NO_INSTRUCTOR_FEE) {
            return BigDecimal.ZERO;
        }
        return unit.quantityFrom(flight.flightDurationMinutes(), AccountingUnitType.MIN);
    }
}
