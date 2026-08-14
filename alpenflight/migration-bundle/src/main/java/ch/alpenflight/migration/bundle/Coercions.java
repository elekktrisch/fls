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
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

public final class Coercions {

    private Coercions() { }

    private static final UUID FAN_OUT_NAMESPACE =
            UUID.fromString("8f3b1c2a-5d47-5e9b-a1f0-6c2d4e8a7b30");

    public static UUID deriveFanOutId(UUID legacyGuid, UUID legacyClubId) {
        MessageDigest sha1;
        try {
            sha1 = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
        ByteBuffer input = ByteBuffer.allocate(16 + 16 + 16);
        putUuid(input, FAN_OUT_NAMESPACE);
        putUuid(input, legacyGuid);
        putUuid(input, legacyClubId);
        byte[] hash = sha1.digest(input.array());

        hash[6] = (byte) ((hash[6] & 0x0F) | 0x50);
        hash[8] = (byte) ((hash[8] & 0x3F) | 0x80);

        ByteBuffer out = ByteBuffer.wrap(hash, 0, 16);
        return new UUID(out.getLong(), out.getLong());
    }

    private static void putUuid(ByteBuffer buffer, UUID uuid) {
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
    }

    public static String bitToTriStateTag(@Nullable Boolean value) {
        if (value == null) {
            return "UNKNOWN";
        }
        return value ? "YES" : "NO";
    }

    public static String legacyIntIdToUuidString(int legacyIntId) {
        if (legacyIntId < 0) {
            throw new IllegalArgumentException(
                    "legacyIntId must be non-negative — sign extension would alias "
                            + "the encoding. Got " + legacyIntId);
        }
        return new UUID(0L, legacyIntId).toString();
    }

    public static @Nullable String optionalLegacyIntIdAsUuidString(
            ResultSet source, String legacyColumn) throws SQLException {
        Integer value = source.getObject(legacyColumn, Integer.class);
        return value == null ? null : legacyIntIdToUuidString(value);
    }

    public static @Nullable UUID readUuidOrNull(JsonNode source, String fieldName) {
        JsonNode node = source.get(fieldName);
        return (node == null || node.isNull()) ? null : UUID.fromString(node.asText());
    }

    public static @Nullable String readStringOrNull(JsonNode source, String fieldName) {
        JsonNode node = source.get(fieldName);
        return (node == null || node.isNull()) ? null : node.asText();
    }

    public static @Nullable Timestamp readTimestampOrNull(JsonNode source, String fieldName) {
        JsonNode node = source.get(fieldName);
        return (node == null || node.isNull())
                ? null
                : Timestamp.from(Instant.parse(node.asText()));
    }

    public static @Nullable Date readDateOrNull(JsonNode source, String fieldName) {
        JsonNode node = source.get(fieldName);
        return (node == null || node.isNull()) ? null : Date.valueOf(node.asText());
    }

    public static void writeOptionalString(
            JsonGenerator target, String fieldName, @Nullable String value)
            throws IOException {
        if (value == null) {
            target.writeNullField(fieldName);
        } else {
            target.writeStringField(fieldName, value);
        }
    }

    private static final String EMPTY_GUID = "00000000-0000-0000-0000-000000000000";

    public static void writeOptionalGuidString(
            JsonGenerator target, String fieldName, @Nullable String value)
            throws IOException {
        if (value == null || EMPTY_GUID.equalsIgnoreCase(value)) {
            target.writeNullField(fieldName);
        } else {
            target.writeStringField(fieldName, value);
        }
    }

    private static final Pattern RECIPIENT_LIST_SEPARATOR = Pattern.compile("[,;\\s]+");

    public static void writeOptionalRecipientList(
            JsonGenerator target, String fieldName, @Nullable String value)
            throws IOException {
        if (value == null) {
            target.writeNullField(fieldName);
            return;
        }
        if (isCanonicalRecipientList(value)) {
            target.writeStringField(fieldName, value);
            return;
        }
        String canonical = RECIPIENT_LIST_SEPARATOR.splitAsStream(value)
                .filter(address -> !address.isEmpty())
                .map(address -> address.toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(","));
        if (canonical.isEmpty()) {
            target.writeNullField(fieldName);
        } else {
            target.writeStringField(fieldName, canonical);
        }
    }

    private static boolean isCanonicalRecipientList(String value) {
        if (value.isEmpty() || value.charAt(value.length() - 1) == ',') {
            return false;
        }
        char previous = ',';
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == ';' || Character.isWhitespace(current)
                    || Character.isUpperCase(current)
                    || (current == ',' && previous == ',')) {
                return false;
            }
            previous = current;
        }
        return true;
    }

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

    public static void writeRequiredTimestampCoalescing(
            JsonGenerator target,
            String fieldName,
            @Nullable Timestamp primary,
            @Nullable Timestamp fallback)
            throws IOException {
        writeRequiredTimestamp(target, fieldName, primary != null ? primary : fallback);
    }

    public static void writeOptionalTimestamp(
            JsonGenerator target, String fieldName, @Nullable Timestamp value)
            throws IOException {
        if (value == null) {
            target.writeNullField(fieldName);
        } else {
            target.writeStringField(fieldName, value.toInstant().toString());
        }
    }

    public static void writeOptionalDate(
            JsonGenerator target, String fieldName, @Nullable Date value)
            throws IOException {
        if (value == null) {
            target.writeNullField(fieldName);
        } else {
            target.writeStringField(fieldName, value.toLocalDate().toString());
        }
    }

    public static void writeOptionalInt(
            JsonGenerator target, String fieldName, @Nullable Integer value)
            throws IOException {
        if (value == null) {
            target.writeNullField(fieldName);
        } else {
            target.writeNumberField(fieldName, value.intValue());
        }
    }

    public static void writeOptionalShort(
            JsonGenerator target, String fieldName, @Nullable Short value)
            throws IOException {
        if (value == null) {
            target.writeNullField(fieldName);
        } else {
            target.writeNumberField(fieldName, value.shortValue());
        }
    }

    public static void writeOptionalLong(
            JsonGenerator target, String fieldName, @Nullable Long value)
            throws IOException {
        if (value == null) {
            target.writeNullField(fieldName);
        } else {
            target.writeNumberField(fieldName, value.longValue());
        }
    }

    public static void writeOptionalBigDecimal(
            JsonGenerator target, String fieldName, @Nullable BigDecimal value)
            throws IOException {
        if (value == null) {
            target.writeNullField(fieldName);
        } else {
            target.writeNumberField(fieldName, value);
        }
    }

    public static @Nullable Integer readIntOrNull(JsonNode source, String fieldName) {
        JsonNode node = source.get(fieldName);
        return (node == null || node.isNull()) ? null : node.intValue();
    }

    public static @Nullable Short readShortOrNull(JsonNode source, String fieldName) {
        JsonNode node = source.get(fieldName);
        return (node == null || node.isNull()) ? null : (short) node.intValue();
    }

    public static @Nullable Long readLongOrNull(JsonNode source, String fieldName) {
        JsonNode node = source.get(fieldName);
        return (node == null || node.isNull()) ? null : node.longValue();
    }

    public static @Nullable BigDecimal readBigDecimalOrNull(JsonNode source, String fieldName) {
        JsonNode node = source.get(fieldName);
        return (node == null || node.isNull()) ? null : node.decimalValue();
    }

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
