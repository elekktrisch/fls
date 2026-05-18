package ch.alpenflight.platform.id;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Typed identifier for the {@code Club} aggregate root. Wraps a {@link UUID}
 * so service / controller / DTO signatures cannot accidentally accept a
 * {@code Person} or {@code User} id in a {@code Club} slot — the confusion
 * the codebase wants to make impossible at the type system level.
 *
 * <p>External form is {@code clb-<uuid>}, where {@code <uuid>} is the JDK's
 * canonical 36-character dashed UUID (ADR 0019). The dashed UUID body keeps
 * the value parsing trivial via {@link UUID#fromString(String)}; the
 * 4-character {@code clb-} prefix lets readers spot a Club id at a glance
 * without standing in the way of any standard UUID tooling.
 *
 * <p>JSON wire format is the external string, configured centrally in
 * {@link TypedIdJacksonModule} (registered with the application
 * {@code ObjectMapper}); this record carries no Jackson annotations on
 * purpose. The {@link Schema} hint tells springdoc to emit
 * {@code type: string} in the OpenAPI spec so the TS codegen consumes a
 * plain string alias.
 *
 * <p>Persistence: the {@code Club} entity field stays {@code UUID} (JPA-
 * friendly); only the getter wraps. Internal entities of the {@code Club}
 * aggregate (e.g. {@code MemberState}) keep raw UUIDs at every layer per
 * S-012 — typed wrappers are reserved for ids that legitimately cross
 * aggregate boundaries.
 */
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
