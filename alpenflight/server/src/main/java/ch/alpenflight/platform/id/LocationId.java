package ch.alpenflight.platform.id;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Typed identifier for the {@code Location} aggregate root. Wraps a
 * {@link UUID} so service / controller / DTO signatures cannot accidentally
 * accept a {@code Club} or {@code Person} id in a {@code Location} slot.
 *
 * <p>External form is {@code loc-<uuid>}, where {@code <uuid>} is the JDK's
 * canonical 36-character dashed UUID (ADR 0019). The 4-character {@code loc-}
 * prefix lets readers spot a Location id at a glance without standing in the
 * way of any standard UUID tooling.
 *
 * <p>JSON wire format is the external string, configured centrally in
 * {@link TypedIdJacksonModule}; this record carries no Jackson annotations on
 * purpose. The {@link Schema} hint tells springdoc to emit {@code type:
 * string} in the OpenAPI spec so the TS codegen consumes a plain string alias.
 *
 * <p>Persistence: the {@code Location} entity field stays {@code UUID} (JPA-
 * friendly); only the getter wraps. Internal child entities of the
 * {@code Location} aggregate (e.g. {@code InOutboundPoint}) keep raw UUIDs at
 * every layer per S-012 — typed wrappers are reserved for ids that
 * legitimately cross aggregate boundaries.
 */
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
