package ch.alpenflight.accounting.domain;

import ch.alpenflight.accounting.domain.RuleBasedDeliveryDetails.Recipient;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record DeliveryDetailsSnapshot(
        List<DeliveryItemDetails> items,
        @Nullable Recipient recipient,
        @Nullable String deliveryInformation,
        @Nullable String additionalInformation) {

    public DeliveryDetailsSnapshot {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static DeliveryDetailsSnapshot empty() {
        return new DeliveryDetailsSnapshot(List.of(), null, null, null);
    }

    public static DeliveryDetailsSnapshot of(RuleBasedDeliveryDetails details) {
        return new DeliveryDetailsSnapshot(
                details.deliveryItems(),
                details.recipient(),
                details.getDeliveryInformation(),
                details.getAdditionalInformation());
    }
}
