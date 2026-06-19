package ch.alpenflight.server.testsupport;

import ch.alpenflight.accounting.domain.Delivery;
import ch.alpenflight.accounting.domain.DeliveryItem;
import ch.alpenflight.accounting.domain.DeliveryProcessState;
import ch.alpenflight.accounting.domain.DeliveryRecipient;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Test-only reflective hydrator for the READ-ONLY {@link Delivery} aggregate +
 * its {@link DeliveryItem} children (the read iteration maps the V4 columns +
 * getters; the write factory is deferred). Per ADR 0027 §4 the read iteration
 * adds no production setter/factory just for seeding — tests populate via
 * reflection here. Never
 * sets {@code operatingClubId}: Hibernate's {@code @TenantId} resolver fills it
 * on INSERT (so a hydrated row saved under the wrong tenant context fails
 * fail-closed, the S-024 contract).
 */
public final class DeliveryTestHydrator {

    private DeliveryTestHydrator() {}

    /** A transient Delivery with field defaults (PREPARED, empty recipient, batch 0, no items). */
    public static Delivery bare() {
        return newInstance(Delivery.class);
    }

    /**
     * A transient Delivery populated for a read IT. {@code operatingClubId} is
     * deliberately NOT set — the resolver fills it on save.
     */
    public static Delivery delivery(DeliveryProcessState state,
                                    @Nullable Integer deliveryNumber,
                                    long batchId,
                                    DeliveryRecipient recipient,
                                    List<DeliveryItem> items) {
        Delivery delivery = newInstance(Delivery.class);
        set(delivery, "processState", state);
        set(delivery, "deliveryNumber", deliveryNumber);
        set(delivery, "batchId", batchId);
        set(delivery, "recipient", recipient);
        set(delivery, "items", new java.util.ArrayList<>(items));
        return delivery;
    }

    /** Sets the linked {@code flightId} on a transient Delivery and returns it (fluent). */
    public static Delivery withFlight(Delivery delivery, UUID flightId) {
        set(delivery, "flightId", flightId);
        return delivery;
    }

    /**
     * A transient DeliveryItem child. {@code operatingClubId} IS set here (the
     * denormalized child stamp the parent's graph carries — DeliveryCreationTestItem
     * precedent), since the child is not a {@code @TenantId} resolver participant.
     */
    public static DeliveryItem item(UUID operatingClubId,
                                    int position,
                                    UUID articleId,
                                    String articleNumber,
                                    @Nullable String itemText,
                                    BigDecimal quantity,
                                    BigDecimal unitPrice,
                                    int discountInPercent,
                                    String unitTypeCode) {
        DeliveryItem item = newInstance(DeliveryItem.class);
        set(item, "operatingClubId", operatingClubId);
        set(item, "position", position);
        set(item, "articleId", articleId);
        set(item, "articleNumber", articleNumber);
        set(item, "itemText", itemText);
        set(item, "quantity", quantity);
        set(item, "unitPrice", unitPrice);
        set(item, "discountInPercent", discountInPercent);
        set(item, "unitTypeCode", unitTypeCode);
        return item;
    }

    private static <T> T newInstance(Class<T> type) {
        try {
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot instantiate " + type.getName(), e);
        }
    }

    private static void set(Object target, String fieldName, @Nullable Object value) {
        try {
            Field field = findField(target.getClass(), fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot set " + fieldName + " on " + target.getClass(), e);
        }
    }

    private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName + " on " + type.getName());
    }
}
