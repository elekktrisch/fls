package ch.alpenflight.platform.id;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Typed identifier for the {@code Person} aggregate root. Wraps a
 * {@link UUID} so service / controller / DTO signatures cannot accidentally
 * accept an {@code Aircraft} or {@code Location} id in a {@code Person} slot.
 *
 * <p>External form is {@code pn-<uuid>} per ADR 0019. The 3-character
 * {@code pn-} prefix lets readers spot a Person id at a glance and protects
 * against the cross-tenant Person + tenant-scoped child shape from leaking
 * a raw UUID without a kind label.
 */
@Schema(
        type = "string",
        pattern = "^pn-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        example = "pn-019e30c3-2c00-7001-8000-000000000001")
public record PersonId(UUID value) {

    public static final String PREFIX = "pn-";

    public PersonId {
        if (value == null) {
            throw new IllegalArgumentException("PersonId value must not be null");
        }
    }

    public static PersonId of(UUID value) {
        return new PersonId(value);
    }

    public static @Nullable PersonId ofNullable(@Nullable UUID value) {
        return value == null ? null : new PersonId(value);
    }

    public static PersonId parse(String external) {
        if (external == null) {
            throw new IllegalArgumentException("PersonId external form must not be null");
        }
        if (!external.startsWith(PREFIX)) {
            throw new IllegalArgumentException(
                    "PersonId external form must start with '" + PREFIX + "', got: " + external);
        }
        String payload = external.substring(PREFIX.length());
        try {
            return new PersonId(UUID.fromString(payload));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "PersonId payload '" + payload + "' is not a valid UUID", e);
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
