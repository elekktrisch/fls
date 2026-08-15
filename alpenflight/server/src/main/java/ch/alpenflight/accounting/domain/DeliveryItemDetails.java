package ch.alpenflight.accounting.domain;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

public record DeliveryItemDetails(
        int position,
        String articleNumber,
        String itemText,
        @Nullable String additionalInformation,
        BigDecimal quantity,
        int discountInPercent,
        String unitType) {

    public DeliveryItemDetails withPosition(int newPosition) {
        return new DeliveryItemDetails(
                newPosition, articleNumber, itemText, additionalInformation, quantity, discountInPercent, unitType);
    }

    public DeliveryItemDetails addQuantity(BigDecimal additionalQuantity) {
        return new DeliveryItemDetails(
                position,
                articleNumber,
                itemText,
                additionalInformation,
                quantity.add(additionalQuantity),
                discountInPercent,
                unitType);
    }
}
