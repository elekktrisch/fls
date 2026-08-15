package ch.alpenflight.accounting.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.jspecify.annotations.Nullable;

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
