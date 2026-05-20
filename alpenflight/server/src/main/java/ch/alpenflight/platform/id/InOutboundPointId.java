package ch.alpenflight.platform.id;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Typed identifier for an {@code InOutboundPoint} — an aggregate-internal
 * child of the {@code Location} aggregate root. Wire form is a plain UUID
 * string (no prefix): ADR 0019 reserves the 3-letter prefix for aggregate
 * roots, and InOutboundPoint is a child entity per ADR 0018.
 *
 * <p>The typed wrapper exists so update payloads can carry the row's id back
 * for stable upsert semantics without confusing it with a {@link LocationId}
 * or {@link CountryId} at compile time.
 */
@Schema(
        type = "string",
        format = "uuid",
        pattern = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        example = "019e30c3-2c00-7001-8000-0000000000aa")
public record InOutboundPointId(UUID value) {

    public InOutboundPointId {
        if (value == null) {
            throw new IllegalArgumentException("InOutboundPointId value must not be null");
        }
    }

    public static InOutboundPointId of(UUID value) {
        return new InOutboundPointId(value);
    }

    public static @Nullable InOutboundPointId ofNullable(@Nullable UUID value) {
        return value == null ? null : new InOutboundPointId(value);
    }

    public static InOutboundPointId parse(String wire) {
        if (wire == null) {
            throw new IllegalArgumentException("InOutboundPointId wire form must not be null");
        }
        try {
            return new InOutboundPointId(UUID.fromString(wire));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "InOutboundPointId payload '" + wire + "' is not a valid UUID", e);
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
