package ch.alpenflight.platform.id;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Typed identifier for the {@code Aircraft} aggregate root. Wraps a
 * {@link UUID} so service / controller / DTO signatures cannot accidentally
 * accept a {@code Location} or {@code Person} id in an {@code Aircraft} slot.
 *
 * <p>External form is {@code ac-<uuid>} per ADR 0019. The 3-character
 * {@code ac-} prefix lets readers spot an Aircraft id at a glance.
 */
@Schema(
        type = "string",
        pattern = "^ac-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        example = "ac-019e30c3-2c00-7001-8000-000000000001")
public record AircraftId(UUID value) {

    public static final String PREFIX = "ac-";

    public AircraftId {
        if (value == null) {
            throw new IllegalArgumentException("AircraftId value must not be null");
        }
    }

    public static AircraftId of(UUID value) {
        return new AircraftId(value);
    }

    public static @Nullable AircraftId ofNullable(@Nullable UUID value) {
        return value == null ? null : new AircraftId(value);
    }

    public static AircraftId parse(String external) {
        if (external == null) {
            throw new IllegalArgumentException("AircraftId external form must not be null");
        }
        if (!external.startsWith(PREFIX)) {
            throw new IllegalArgumentException(
                    "AircraftId external form must start with '" + PREFIX + "', got: " + external);
        }
        String payload = external.substring(PREFIX.length());
        try {
            return new AircraftId(UUID.fromString(payload));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "AircraftId payload '" + payload + "' is not a valid UUID", e);
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
