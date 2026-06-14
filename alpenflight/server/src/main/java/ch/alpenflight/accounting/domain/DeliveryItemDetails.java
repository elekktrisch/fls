package ch.alpenflight.accounting.domain;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * One emitted invoice line. The rules engine accumulates these into a
 * {@link RuleBasedDeliveryDetails}; the test harness compares the emitted set
 * field-by-field against the stored expected set, so every component here is
 * part of the compared value. The legacy {@code DeliveryItemId} is a generated
 * key, not compared, and is deliberately omitted.
 */
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
