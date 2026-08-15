package ch.alpenflight.accounting.domain;

import ch.alpenflight.accounting.domain.RuleBasedDeliveryDetails.Recipient;
import java.util.List;
import org.jspecify.annotations.Nullable;

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

    private void applyFallbacks(RuleBasedDeliveryDetails accumulator, MatchableFlight flight) {
        int balanceTypeId = flight.flightCostBalanceTypeId();

        if (balanceTypeId == COSTS_PAID_BY_PERSON) {
            setIfPresent(accumulator, flight.flightCostInvoiceRecipient());
        }
        if (balanceTypeId == PILOT_PAYS_ALL_COSTS || balanceTypeId == NO_INSTRUCTOR_FEE) {
            setIfPresent(accumulator, flight.pilot());
        }
    }

    private void setIfPresent(RuleBasedDeliveryDetails accumulator, @Nullable Recipient recipient) {
        if (recipient != null) {
            accumulator.setRecipient(recipient);
        }
    }
}
