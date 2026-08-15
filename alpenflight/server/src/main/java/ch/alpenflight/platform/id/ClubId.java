package ch.alpenflight.platform.id;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(
        type = "string",
        pattern = "^clb-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        example = "clb-019e30c3-2c00-7001-8000-000000000001")
public record ClubId(UUID value) {

    public static final String PREFIX = "clb-";

    public ClubId {
        if (value == null) {
            throw new IllegalArgumentException("ClubId value must not be null");
        }
    }

    public static ClubId of(UUID value) {
        return new ClubId(value);
    }

    public static @Nullable ClubId ofNullable(@Nullable UUID value) {
        return value == null ? null : new ClubId(value);
    }

    public static ClubId parse(String external) {
        if (external == null) {
            throw new IllegalArgumentException("ClubId external form must not be null");
        }
        if (!external.startsWith(PREFIX)) {
            throw new IllegalArgumentException(
                    "ClubId external form must start with '" + PREFIX + "', got: " + external);
        }
        String payload = external.substring(PREFIX.length());
        try {
            return new ClubId(UUID.fromString(payload));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "ClubId payload '" + payload + "' is not a valid UUID", e);
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
