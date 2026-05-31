package ch.alpenflight.migration.bundle;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Fail-closed redactor for the sampled-value diff. The diff emitter never
 * writes a raw column value unless the column was explicitly opted into the
 * value comparison ({@code @ParitySentinel}) <em>and</em> is not on the PII
 * deny-list. Everything else — including any column a future mapper adds
 * without thinking about the report — collapses to {@link #REDACTED}.
 *
 * <p>The polarity is the load-bearing decision (S-187a Security plan): a
 * deny-list alone would leak an unknown column; default-deny does not. The PII
 * deny-list is a second layer for columns that are sentinels yet identity-
 * attributable. It mirrors {@code alpenflight/database/tenant-rules.yaml}
 * {@code pii_columns}; that file stays authoritative, and the drift guard (the
 * bundle set ⊇ every tenant-rules PII column whose table maps to a known
 * mapper) is a server-side test where both are on the classpath.
 */
public final class ParityValueRedactor {

    public static final String REDACTED = "[redacted]";

    /** New-stack PII columns per entity. Mirror of {@code tenant-rules.yaml}. */
    private static final Map<EntityType, Set<String>> PII_COLUMNS = piiColumns();

    private ParityValueRedactor() { }

    /**
     * Returns {@code value} only when {@code column} is a sentinel for this
     * mapper and is not PII; otherwise {@link #REDACTED}.
     */
    public static String emit(
            EntityType entity, String column, @Nullable String value, Set<String> sentinelColumns) {
        if (!sentinelColumns.contains(column) || isPii(entity, column)) {
            return REDACTED;
        }
        return value == null ? "" : value;
    }

    public static boolean isPii(EntityType entity, String column) {
        return PII_COLUMNS.getOrDefault(entity, Set.of()).contains(column);
    }

    private static Map<EntityType, Set<String>> piiColumns() {
        Map<EntityType, Set<String>> columns = new EnumMap<>(EntityType.class);
        columns.put(EntityType.USER, Set.of(
                "username", "friendly_name", "notification_email", "phone_number"));
        columns.put(EntityType.PERSON, Set.of(
                "firstname", "lastname", "midname",
                "email_private", "email_business",
                "private_phone", "mobile_phone", "business_phone", "fax_number",
                "licence_number"));
        return columns;
    }
}
