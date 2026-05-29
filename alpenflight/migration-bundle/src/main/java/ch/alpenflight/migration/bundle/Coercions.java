package ch.alpenflight.migration.bundle;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Stateless coercion helpers shared by every mapper. Static methods only — the
 * per-row allocation budget in the mapper hot path is zero.
 */
public final class Coercions {

    private Coercions() { }

    /** Null preserves the third state required by the S-129 string-enum encoding. */
    public static String bitToTriStateTag(@Nullable Boolean value) {
        if (value == null) {
            return "UNKNOWN";
        }
        return value ? "YES" : "NO";
    }

    /**
     * Synthetic UUID encoding of a legacy INT primary key — used by mappers
     * for reference tables whose legacy PK is INT (Language, ClubState).
     * The new-stack {@code legacy_id_map_<entity>} byte format is fixed at
     * (UUID, UUID) per {@link LegacyIdMapWriter}; this helper widens the
     * INT into the most-significant 32 bits of a zero-filled UUID so the
     * encoding is deterministic and reversible by inspection.
     *
     * <p>Cross-mapper invariant: any mapper that emits an FK to a Language
     * / ClubState row MUST encode the legacy INT through this helper so the
     * downstream join against {@code legacy_id_map_<entity>.legacy_guid}
     * resolves.
     */
    public static String legacyIntIdToUuidString(int legacyIntId) {
        return new UUID(0L, legacyIntId).toString();
    }

    /** Returns null when the field is absent or {@code JsonNull}. */
    public static @Nullable UUID readUuidOrNull(JsonNode source, String fieldName) {
        JsonNode node = source.get(fieldName);
        return (node == null || node.isNull()) ? null : UUID.fromString(node.asText());
    }

    /** Returns null when the field is absent or {@code JsonNull}. */
    public static @Nullable String readStringOrNull(JsonNode source, String fieldName) {
        JsonNode node = source.get(fieldName);
        return (node == null || node.isNull()) ? null : node.asText();
    }

    /** ISO-8601 instant → {@link Timestamp}; null when the field is absent or null. */
    public static @Nullable Timestamp readTimestampOrNull(JsonNode source, String fieldName) {
        JsonNode node = source.get(fieldName);
        return (node == null || node.isNull())
                ? null
                : Timestamp.from(Instant.parse(node.asText()));
    }

    /** ISO-8601 local date → {@link Date}; null when the field is absent or null. */
    public static @Nullable Date readDateOrNull(JsonNode source, String fieldName) {
        JsonNode node = source.get(fieldName);
        return (node == null || node.isNull()) ? null : Date.valueOf(node.asText());
    }

    /** Emit {@code "name": "value"} or {@code "name": null}. */
    public static void writeOptionalString(
            JsonGenerator target, String fieldName, @Nullable String value)
            throws IOException {
        if (value == null) {
            target.writeNullField(fieldName);
        } else {
            target.writeStringField(fieldName, value);
        }
    }

    /** Emit ISO-8601 instant; {@code Timestamp} must not be null. */
    public static void writeRequiredTimestamp(
            JsonGenerator target, String fieldName, Timestamp value) throws IOException {
        target.writeStringField(fieldName, value.toInstant().toString());
    }

    /** Emit ISO-8601 instant or null. */
    public static void writeOptionalTimestamp(
            JsonGenerator target, String fieldName, @Nullable Timestamp value)
            throws IOException {
        if (value == null) {
            target.writeNullField(fieldName);
        } else {
            target.writeStringField(fieldName, value.toInstant().toString());
        }
    }

    /** Emit ISO-8601 local date or null. */
    public static void writeOptionalDate(
            JsonGenerator target, String fieldName, @Nullable Date value)
            throws IOException {
        if (value == null) {
            target.writeNullField(fieldName);
        } else {
            target.writeStringField(fieldName, value.toLocalDate().toString());
        }
    }
}
