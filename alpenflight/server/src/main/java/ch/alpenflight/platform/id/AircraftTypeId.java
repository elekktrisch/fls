package ch.alpenflight.platform.id;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(
        type = "string",
        format = "uuid",
        pattern = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        example = "019e2e15-2c00-7af9-8000-000000002af9")
public record AircraftTypeId(UUID value) {

    public AircraftTypeId {
        if (value == null) {
            throw new IllegalArgumentException("AircraftTypeId value must not be null");
        }
    }

    public static AircraftTypeId of(UUID value) {
        return new AircraftTypeId(value);
    }

    public static @Nullable AircraftTypeId ofNullable(@Nullable UUID value) {
        return value == null ? null : new AircraftTypeId(value);
    }

    public static AircraftTypeId parse(String wire) {
        if (wire == null) {
            throw new IllegalArgumentException("AircraftTypeId wire form must not be null");
        }
        try {
            return new AircraftTypeId(UUID.fromString(wire));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "AircraftTypeId payload '" + wire + "' is not a valid UUID", e);
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
