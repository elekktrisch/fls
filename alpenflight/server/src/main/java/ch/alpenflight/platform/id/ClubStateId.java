package ch.alpenflight.platform.id;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Typed identifier for the {@code ClubState} reference row. Wire form is a
 * plain UUID string — see {@link CountryId} for rationale (reference rows
 * are not aggregate roots per ADR 0018; prefixes from ADR 0019 reserved
 * for roots). Exists to prevent compile-time confusion with
 * {@link CountryId} or other unrelated {@link UUID} arguments.
 */
@Schema(
        type = "string",
        format = "uuid",
        pattern = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        example = "019e2e15-2c00-7bb8-8000-000000000bb8")
public record ClubStateId(UUID value) {

    public ClubStateId {
        if (value == null) {
            throw new IllegalArgumentException("ClubStateId value must not be null");
        }
    }

    public static ClubStateId of(UUID value) {
        return new ClubStateId(value);
    }

    public static @Nullable ClubStateId ofNullable(@Nullable UUID value) {
        return value == null ? null : new ClubStateId(value);
    }

    public static ClubStateId parse(String wire) {
        if (wire == null) {
            throw new IllegalArgumentException("ClubStateId wire form must not be null");
        }
        try {
            return new ClubStateId(UUID.fromString(wire));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "ClubStateId payload '" + wire + "' is not a valid UUID", e);
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
