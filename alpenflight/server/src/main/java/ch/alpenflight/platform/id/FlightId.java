package ch.alpenflight.platform.id;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Typed identifier for the {@code Flight} aggregate root. Wraps a
 * {@link UUID} so service / controller / DTO signatures cannot accidentally
 * accept an {@code AircraftId} or {@code PersonId} in a {@code FlightId} slot.
 *
 * <p>External form is {@code fl-<uuid>} per ADR 0019.
 */
@Schema(
        type = "string",
        pattern = "^fl-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        example = "fl-019e30c3-2c00-7001-8000-000000000001")
public record FlightId(UUID value) {

    public static final String PREFIX = "fl-";

    public FlightId {
        if (value == null) {
            throw new IllegalArgumentException("FlightId value must not be null");
        }
    }

    public static FlightId of(UUID value) {
        return new FlightId(value);
    }

    public static @Nullable FlightId ofNullable(@Nullable UUID value) {
        return value == null ? null : new FlightId(value);
    }

    public static FlightId parse(String external) {
        if (external == null) {
            throw new IllegalArgumentException("FlightId external form must not be null");
        }
        if (!external.startsWith(PREFIX)) {
            throw new IllegalArgumentException(
                    "FlightId external form must start with '" + PREFIX + "', got: " + external);
        }
        String payload = external.substring(PREFIX.length());
        try {
            return new FlightId(UUID.fromString(payload));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "FlightId payload '" + payload + "' is not a valid UUID", e);
        }
    }

    public String toExternal() {
        return PREFIX + value.toString();
    }

    @Override
    public String toString() {
        return toExternal();
    }
}
