package ch.alpenflight.accounting.domain;

import ch.alpenflight.accounting.domain.RuleBasedDeliveryDetails.Recipient;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record RuleFilterInput(
        UUID filterId,
        @Nullable Recipient recipientTarget,
        @Nullable String articleNumber,
        @Nullable AccountingUnitType accountingUnitType,
        FilterConfig filterConfig) {

    public static RuleFilterInput of(
            UUID filterId, @Nullable Recipient recipientTarget, FilterConfig filterConfig) {
        return new RuleFilterInput(filterId, recipientTarget, null, null, filterConfig);
    }
}
