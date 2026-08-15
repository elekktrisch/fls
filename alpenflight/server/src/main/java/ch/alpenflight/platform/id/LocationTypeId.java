package ch.alpenflight.platform.id;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(
        type = "string",
        format = "uuid",
        pattern = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        example = "019e2e15-2c00-72c9-8000-0000000032c9")
public record LocationTypeId(UUID value) {

    public LocationTypeId {
        if (value == null) {
            throw new IllegalArgumentException("LocationTypeId value must not be null");
        }
    }

    public static LocationTypeId of(UUID value) {
        return new LocationTypeId(value);
    }

    public static @Nullable LocationTypeId ofNullable(@Nullable UUID value) {
        return value == null ? null : new LocationTypeId(value);
    }

    public static LocationTypeId parse(String wire) {
        if (wire == null) {
            throw new IllegalArgumentException("LocationTypeId wire form must not be null");
        }
        try {
            return new LocationTypeId(UUID.fromString(wire));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "LocationTypeId payload '" + wire + "' is not a valid UUID", e);
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
