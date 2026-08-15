package ch.alpenflight.platform.id;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(
        type = "string",
        pattern = "^loc-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        example = "loc-019e30c3-2c00-7001-8000-000000000001")
public record LocationId(UUID value) {

    public static final String PREFIX = "loc-";

    public LocationId {
        if (value == null) {
            throw new IllegalArgumentException("LocationId value must not be null");
        }
    }

    public static LocationId of(UUID value) {
        return new LocationId(value);
    }

    public static @Nullable LocationId ofNullable(@Nullable UUID value) {
        return value == null ? null : new LocationId(value);
    }

    public static LocationId parse(String external) {
        if (external == null) {
            throw new IllegalArgumentException("LocationId external form must not be null");
        }
        if (!external.startsWith(PREFIX)) {
            throw new IllegalArgumentException(
                    "LocationId external form must start with '" + PREFIX + "', got: " + external);
        }
        String payload = external.substring(PREFIX.length());
        try {
            return new LocationId(UUID.fromString(payload));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "LocationId payload '" + payload + "' is not a valid UUID", e);
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
