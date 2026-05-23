package ch.alpenflight.platform.id;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Typed identifier for the {@code AircraftState} reference row (OK,
 * INFORMATION, ATTENTION, MALFUNCTION, MAINTENANCE, UNINSURED, END_OF_LIFE).
 * Plain UUID wire form per the reference-data convention.
 */
@Schema(
        type = "string",
        format = "uuid",
        pattern = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        example = "019e2e15-2c00-7ee0-8000-000000002ee0")
public record AircraftStateId(UUID value) {

    public AircraftStateId {
        if (value == null) {
            throw new IllegalArgumentException("AircraftStateId value must not be null");
        }
    }

    public static AircraftStateId of(UUID value) {
        return new AircraftStateId(value);
    }

    public static @Nullable AircraftStateId ofNullable(@Nullable UUID value) {
        return value == null ? null : new AircraftStateId(value);
    }

    public static AircraftStateId parse(String wire) {
        if (wire == null) {
            throw new IllegalArgumentException("AircraftStateId wire form must not be null");
        }
        try {
            return new AircraftStateId(UUID.fromString(wire));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "AircraftStateId payload '" + wire + "' is not a valid UUID", e);
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
