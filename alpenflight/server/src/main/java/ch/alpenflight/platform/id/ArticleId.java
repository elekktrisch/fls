package ch.alpenflight.platform.id;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(
        type = "string",
        pattern = "^art-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        example = "art-019e30c3-2c00-7001-8000-000000000001")
public record ArticleId(UUID value) {

    public static final String PREFIX = "art-";

    public ArticleId {
        if (value == null) {
            throw new IllegalArgumentException("ArticleId value must not be null");
        }
    }

    public static ArticleId of(UUID value) {
        return new ArticleId(value);
    }

    public static @Nullable ArticleId ofNullable(@Nullable UUID value) {
        return value == null ? null : new ArticleId(value);
    }

    public static ArticleId parse(String external) {
        if (external == null) {
            throw new IllegalArgumentException("ArticleId external form must not be null");
        }
        if (!external.startsWith(PREFIX)) {
            throw new IllegalArgumentException(
                    "ArticleId external form must start with '" + PREFIX + "', got: " + external);
        }
        String payload = external.substring(PREFIX.length());
        try {
            return new ArticleId(UUID.fromString(payload));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "ArticleId payload '" + payload + "' is not a valid UUID", e);
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
