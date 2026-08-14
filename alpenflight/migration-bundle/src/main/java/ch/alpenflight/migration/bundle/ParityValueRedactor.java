package ch.alpenflight.migration.bundle;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public final class ParityValueRedactor {

    public static final String REDACTED = "[redacted]";

    private static final Map<EntityType, Set<String>> PII_COLUMNS = piiColumns();

    private ParityValueRedactor() { }

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
                "username", "friendly_name", "notification_email", "phone_number", "remarks"));
        columns.put(EntityType.PERSON, Set.of(
                "firstname", "lastname", "midname", "company_name",
                "address_line1", "address_line2", "zip", "city", "region",
                "private_phone", "mobile_phone", "business_phone", "fax_number",
                "email_private", "email_business", "birthday",
                "licence_number", "spot_link"));
        columns.put(EntityType.PERSON_CLUB, Set.of("member_number"));
        return columns;
    }
}
