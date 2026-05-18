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
 * <p>External form is {@code clb_<26-char Crockford Base32>} (ADR 0019).
 * JSON wire format is the external string, configured centrally in
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
@Schema(type = "string", pattern = "^clb_[0-9a-z]{26}$", example = "clb_019e30c32c0070018000000000000001")
public record ClubId(UUID value) {

    public static final String PREFIX = "clb_";

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
        return new ClubId(IdEncoding.decode(external.substring(PREFIX.length())));
    }

    public String toExternal() {
        return PREFIX + IdEncoding.encode(value);
    }

    @Override
    public String toString() {
        return toExternal();
    }
}
