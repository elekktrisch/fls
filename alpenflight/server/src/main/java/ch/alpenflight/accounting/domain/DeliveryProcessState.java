package ch.alpenflight.accounting.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.jspecify.annotations.Nullable;

/**
 * Delivery process-state values. The V4 schema promotes state to first-class on
 * Delivery as a sparse SMALLINT ({@code process_state_id}); this enum carries the
 * legacy code per row. Persistence uses {@link DeliveryProcessStateConverter}
 * with the explicit code mapping — {@code @Enumerated(ORDINAL)} cannot express
 * the gaps (10/20/30/99).
 *
 * <p>The codes mirror the V4 migration header's reshape of legacy
 * {@code flight.process_state_id} + {@code delivery.is_further_processed} into the
 * one {@code process_state_id}: 10 Prepared, 20 Booked, 30 Error, 99 Cancelled.
 * This read iteration only displays the state; the transition rules
 * ({@code book()} preconditions, 409-on-Booked) are deferred to the write side.
 */
public enum DeliveryProcessState {

    PREPARED(10),
    BOOKED(20),
    ERROR(30),
    CANCELLED(99);

    private final short code;

    DeliveryProcessState(int code) {
        this.code = (short) code;
    }

    public short code() {
        return code;
    }

    public static DeliveryProcessState fromCode(short code) {
        for (DeliveryProcessState s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        throw new IllegalArgumentException(
                "Unknown DeliveryProcessState code: " + code + " (expected one of {10, 20, 30, 99})");
    }

    /**
     * JPA converter for the sparse SMALLINT {@code process_state_id} discriminator.
     * {@code autoApply = false}: {@link Delivery} opts in via {@code @Convert} so
     * other Short fields are not silently rewritten (the FlightAircraftType
     * precedent).
     */
    @Converter(autoApply = false)
    public static class DeliveryProcessStateConverter
            implements AttributeConverter<DeliveryProcessState, Short> {

        @Override
        public @Nullable Short convertToDatabaseColumn(@Nullable DeliveryProcessState attribute) {
            return attribute == null ? null : attribute.code;
        }

        @Override
        public @Nullable DeliveryProcessState convertToEntityAttribute(@Nullable Short dbData) {
            return dbData == null ? null : fromCode(dbData);
        }
    }
}
