package ch.alpenflight.platform.id;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(
        type = "string",
        pattern = "^eml-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        example = "eml-019e30c3-2c00-7001-8000-000000000001")
public record EmailTemplateId(UUID value) {

    public static final String PREFIX = "eml-";

    public EmailTemplateId {
        if (value == null) {
            throw new IllegalArgumentException("EmailTemplateId value must not be null");
        }
    }

    public static EmailTemplateId of(UUID value) {
        return new EmailTemplateId(value);
    }

    public static @Nullable EmailTemplateId ofNullable(@Nullable UUID value) {
        return value == null ? null : new EmailTemplateId(value);
    }

    public static EmailTemplateId parse(String external) {
        if (external == null) {
            throw new IllegalArgumentException("EmailTemplateId external form must not be null");
        }
        if (!external.startsWith(PREFIX)) {
            throw new IllegalArgumentException(
                    "EmailTemplateId external form must start with '" + PREFIX + "', got: " + external);
        }
        String payload = external.substring(PREFIX.length());
        try {
            return new EmailTemplateId(UUID.fromString(payload));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "EmailTemplateId payload '" + payload + "' is not a valid UUID", e);
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
