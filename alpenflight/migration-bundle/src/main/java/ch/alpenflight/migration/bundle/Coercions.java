package ch.alpenflight.migration.bundle;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Stateless coercion helpers shared by every mapper. Static methods only — the
 * per-row allocation budget in the mapper hot path is zero.
 */
public final class Coercions {

    private Coercions() { }

    /**
     * Pinned namespace for fan-out id derivation. <strong>This constant must
     * NEVER change.</strong> {@link #deriveFanOutId} is a name-based UUIDv5
     * over {@code (namespace ++ legacyGuid bytes ++ legacyClubId bytes)}; a
     * re-POST of the same legacy bundle must reproduce byte-identical ids so
     * ingest UPSERTs idempotently (matching CLUB's {@code ON CONFLICT} path).
     * Re-pinning this value would mint a fresh id for every previously-ingested
     * fan-out row, breaking that idempotency. Randomly generated once for J-0b
     * and frozen here.
     */
    private static final UUID FAN_OUT_NAMESPACE =
            UUID.fromString("8f3b1c2a-5d47-5e9b-a1f0-6c2d4e8a7b30");

    /**
     * Deterministically derives a fan-out replica id from a shared legacy
     * masterdata GUID and the <em>legacy</em> club id it is being fanned out
     * for. One legacy row referenced by N clubs yields N distinct ids (one per
     * club), so the tenant-partitioned new stack (ADR 0008) gets a
     * {@code club_id}-distinct PK per replica with no producer-side mint and no
     * {@code RETURNING} round-trip.
     *
     * <p>Computed as a name-based <strong>UUIDv5</strong> (RFC 4122 §4.3:
     * SHA-1 over {@link #FAN_OUT_NAMESPACE} concatenated with the 16
     * big-endian bytes of {@code legacyGuid} then {@code legacyClubId};
     * version nibble forced to {@code 0x5}, variant bits to {@code 0b10}).
     * Keying is strictly on the <em>legacy</em> club id — the only id stable
     * across producer and referencer (ingest never sees it). Same inputs →
     * same UUID forever; the byte-stability re-ingest depends on is anchored by
     * the pinned namespace above.
     */
    public static UUID deriveFanOutId(UUID legacyGuid, UUID legacyClubId) {
        MessageDigest sha1;
        try {
            sha1 = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 is a mandated JCA algorithm — its absence is unrecoverable.
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
        ByteBuffer input = ByteBuffer.allocate(16 + 16 + 16);
        putUuid(input, FAN_OUT_NAMESPACE);
        putUuid(input, legacyGuid);
        putUuid(input, legacyClubId);
        byte[] hash = sha1.digest(input.array());

        hash[6] = (byte) ((hash[6] & 0x0F) | 0x50);   // version 5
        hash[8] = (byte) ((hash[8] & 0x3F) | 0x80);   // variant 0b10

        ByteBuffer out = ByteBuffer.wrap(hash, 0, 16);
        return new UUID(out.getLong(), out.getLong());
    }

    private static void putUuid(ByteBuffer buffer, UUID uuid) {
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
    }

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
     * INT into the least-significant bits of an otherwise zero-filled UUID
     * (most-significant 64 bits = 0; least-significant 64 bits hold the
     * sign-extended INT) so the encoding is deterministic and reversible
     * by inspection. Legacy IDs are non-negative; a negative argument is
     * rejected to keep the encoding bijective.
     *
     * <p>Cross-mapper invariant: any mapper that emits an FK to a Language
     * / ClubState row MUST encode the legacy INT through this helper so the
     * downstream join against {@code legacy_id_map_<entity>.legacy_guid}
     * resolves.
     */
    public static String legacyIntIdToUuidString(int legacyIntId) {
        if (legacyIntId < 0) {
            throw new IllegalArgumentException(
                    "legacyIntId must be non-negative — sign extension would alias "
                            + "the encoding. Got " + legacyIntId);
        }
        return new UUID(0L, legacyIntId).toString();
    }

    /**
     * Convenience for FK columns that are nullable INT in legacy: returns
     * null when the column is SQL NULL, otherwise the encoded synthetic
     * UUID via {@link #legacyIntIdToUuidString}. Lifted out of the
     * flight-group mappers (Location / Aircraft / Flight) where the same
     * 4-line guard was duplicated.
     */
    public static @Nullable String optionalLegacyIntIdAsUuidString(
            ResultSet source, String legacyColumn) throws SQLException {
        Integer value = source.getObject(legacyColumn, Integer.class);
        return value == null ? null : legacyIntIdToUuidString(value);
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

    /**
     * Emit ISO-8601 instant; the destination column is {@code NOT NULL}, so a
     * NULL legacy value cannot round-trip. Fail with a diagnostic message that
     * names the column rather than letting {@code value.toInstant()} surface as
     * an opaque {@link NullPointerException} (null getMessage) — the export's
     * per-entity error handler reports the message, so a clear cause beats a
     * bare {@code : null} that forces an entity-by-entity CI grind.
     */
    public static void writeRequiredTimestamp(
            JsonGenerator target, String fieldName, @Nullable Timestamp value)
            throws IOException {
        if (value == null) {
            throw new IllegalStateException(
                    "Required timestamp column '" + fieldName + "' is NULL in the "
                            + "legacy row, but the destination column is NOT NULL. "
                            + "Either the legacy data has an unexpected NULL or the "
                            + "mapper must treat this column as optional.");
        }
        target.writeStringField(fieldName, value.toInstant().toString());
    }

    /**
     * Emit a {@code NOT NULL} audit timestamp whose <em>legacy</em> source column
     * is nullable, coalescing to a fallback. Legacy {@code ModifiedOn} is NULL for
     * a row that was created but never modified, yet the new-stack
     * {@code modified_on} is {@code NOT NULL} (audit invariant). A never-modified
     * row's last-modified equals its creation, so we emit
     * {@code COALESCE(primary, fallback)} — parity-correct, and it preserves the
     * NOT-NULL invariant without relaxing the schema (J-0c T-19).
     *
     * <p>If <em>both</em> are NULL the destination cannot be satisfied; we fail
     * with the same column-naming diagnostic as {@link #writeRequiredTimestamp}
     * (the fallback {@code CreatedOn} is itself NOT NULL, so this signals genuinely
     * malformed legacy data rather than the expected never-modified case).
     */
    public static void writeRequiredTimestampCoalescing(
            JsonGenerator target,
            String fieldName,
            @Nullable Timestamp primary,
            @Nullable Timestamp fallback)
            throws IOException {
        writeRequiredTimestamp(target, fieldName, primary != null ? primary : fallback);
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

    /** Emit integer or null. */
    public static void writeOptionalInt(
            JsonGenerator target, String fieldName, @Nullable Integer value)
            throws IOException {
        if (value == null) {
            target.writeNullField(fieldName);
        } else {
            target.writeNumberField(fieldName, value.intValue());
        }
    }

    /** Emit short or null. */
    public static void writeOptionalShort(
            JsonGenerator target, String fieldName, @Nullable Short value)
            throws IOException {
        if (value == null) {
            target.writeNullField(fieldName);
        } else {
            target.writeNumberField(fieldName, value.shortValue());
        }
    }

    /** Emit long or null. */
    public static void writeOptionalLong(
            JsonGenerator target, String fieldName, @Nullable Long value)
            throws IOException {
        if (value == null) {
            target.writeNullField(fieldName);
        } else {
            target.writeNumberField(fieldName, value.longValue());
        }
    }

    /** Emit BigDecimal or null. */
    public static void writeOptionalBigDecimal(
            JsonGenerator target, String fieldName, @Nullable BigDecimal value)
            throws IOException {
        if (value == null) {
            target.writeNullField(fieldName);
        } else {
            target.writeNumberField(fieldName, value);
        }
    }

    /** Returns null when the field is absent or {@code JsonNull}. */
    public static @Nullable Integer readIntOrNull(JsonNode source, String fieldName) {
        JsonNode node = source.get(fieldName);
        return (node == null || node.isNull()) ? null : node.intValue();
    }

    /** Returns null when the field is absent or {@code JsonNull}. */
    public static @Nullable Short readShortOrNull(JsonNode source, String fieldName) {
        JsonNode node = source.get(fieldName);
        return (node == null || node.isNull()) ? null : (short) node.intValue();
    }

    /** Returns null when the field is absent or {@code JsonNull}. */
    public static @Nullable Long readLongOrNull(JsonNode source, String fieldName) {
        JsonNode node = source.get(fieldName);
        return (node == null || node.isNull()) ? null : node.longValue();
    }

    /** Returns null when the field is absent or {@code JsonNull}. */
    public static @Nullable BigDecimal readBigDecimalOrNull(JsonNode source, String fieldName) {
        JsonNode node = source.get(fieldName);
        return (node == null || node.isNull()) ? null : node.decimalValue();
    }

    /**
     * Binds a SMALLINT column on {@code target} preserving the primitive
     * type contract: {@code setShort} when the JSON field carries a value,
     * {@code setNull(SMALLINT)} when it is null or absent. Lifted out of
     * {@code FlightMapper.readEntity} + {@code FlightCrewMapper.readEntity}
     * where the same 4-line guard was duplicated six times.
     */
    public static void bindShortOrNull(
            PreparedStatement target, int position, JsonNode source, String fieldName)
            throws SQLException {
        Short value = readShortOrNull(source, fieldName);
        if (value == null) {
            target.setNull(position, Types.SMALLINT);
        } else {
            target.setShort(position, value);
        }
    }
}
