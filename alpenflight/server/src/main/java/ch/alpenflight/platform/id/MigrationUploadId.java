package ch.alpenflight.platform.id;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Typed identifier for the S-140 {@code MigrationUpload} row.
 *
 * <p>Unlike the cross-bounded-context aggregate roots in ADR 0019's prefix
 * registry, {@code MigrationUpload} is a short-lived (24h-TTL) credential
 * row — not a domain aggregate that surfaces in URLs, audit logs, and
 * push notifications across modules. The external form is therefore the
 * raw canonical UUID without a prefix. Reversible later if cross-context
 * surfaces emerge.
 */
@Schema(
        type = "string",
        format = "uuid",
        example = "019e30c3-2c00-7001-8000-000000000001")
public record MigrationUploadId(UUID value) {

    public MigrationUploadId {
        if (value == null) {
            throw new IllegalArgumentException("MigrationUploadId value must not be null");
        }
    }

    public static MigrationUploadId of(UUID value) {
        return new MigrationUploadId(value);
    }

    public static @Nullable MigrationUploadId ofNullable(@Nullable UUID value) {
        return value == null ? null : new MigrationUploadId(value);
    }

    public static MigrationUploadId parse(String external) {
        if (external == null) {
            throw new IllegalArgumentException("MigrationUploadId external form must not be null");
        }
        try {
            return new MigrationUploadId(UUID.fromString(external));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "MigrationUploadId external form must be a canonical UUID, got: " + external, e);
        }
    }

    public String toExternal() {
        return value.toString();
    }

    @Override
    public String toString() {
        return toExternal();
    }
}
