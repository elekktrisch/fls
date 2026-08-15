package ch.alpenflight.platform.id;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(
        type = "string",
        pattern = "^ft-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        example = "ft-019e30c3-2c00-7001-8000-000000000001")
public record FlightTypeId(UUID value) {

    public static final String PREFIX = "ft-";

    public FlightTypeId {
        if (value == null) {
            throw new IllegalArgumentException("FlightTypeId value must not be null");
        }
    }

    public static FlightTypeId of(UUID value) {
        return new FlightTypeId(value);
    }

    public static @Nullable FlightTypeId ofNullable(@Nullable UUID value) {
        return value == null ? null : new FlightTypeId(value);
    }

    public static FlightTypeId parse(String external) {
        if (external == null) {
            throw new IllegalArgumentException("FlightTypeId external form must not be null");
        }
        if (!external.startsWith(PREFIX)) {
            throw new IllegalArgumentException(
                    "FlightTypeId external form must start with '" + PREFIX + "', got: " + external);
        }
        String payload = external.substring(PREFIX.length());
        try {
            return new FlightTypeId(UUID.fromString(payload));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "FlightTypeId payload '" + payload + "' is not a valid UUID", e);
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
