package ch.alpenflight.platform.id;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Typed identifier for the {@code Country} reference row. Unlike
 * {@link ClubId} (aggregate root, prefixed external form), system-global
 * reference lookups travel as plain UUID strings on the wire — ADR 0019
 * reserves the 3-letter prefix for aggregate roots, and reference rows
 * are not aggregates per ADR 0018. The wrapper exists for compile-time
 * safety so a {@code CountryId} cannot be passed where a
 * {@link ClubStateId} is expected.
 */
@Schema(
        type = "string",
        format = "uuid",
        pattern = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        example = "019e2e15-2c00-74be-8000-0000000004be")
public record CountryId(UUID value) {

    public CountryId {
        if (value == null) {
            throw new IllegalArgumentException("CountryId value must not be null");
        }
    }

    public static CountryId of(UUID value) {
        return new CountryId(value);
    }

    public static @Nullable CountryId ofNullable(@Nullable UUID value) {
        return value == null ? null : new CountryId(value);
    }

    public static CountryId parse(String wire) {
        if (wire == null) {
            throw new IllegalArgumentException("CountryId wire form must not be null");
        }
        try {
            return new CountryId(UUID.fromString(wire));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "CountryId payload '" + wire + "' is not a valid UUID", e);
        }
    }

    public String toWire() {
        return value.toString();
    }

    @Override
    public String toString() {
        return toWire();
    }
}
