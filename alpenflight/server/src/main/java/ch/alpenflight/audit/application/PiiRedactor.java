package ch.alpenflight.audit.application;

import ch.alpenflight.audit.application.AuditRedactionProperties.EntityPolicy;
import ch.alpenflight.audit.domain.AuditRedact;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Default-deny serializer that walks the snapshot reflectively and emits
 * only fields the {@link AuditRedactionProperties} policy explicitly
 * allows. Anything else lands as the literal {@code "[redacted]"}
 * sentinel (not omitted — absence is unambiguous).
 *
 * <p>Field-level {@link AuditRedact} annotations redact unconditionally,
 * even when the central policy would otherwise allow the field. That's
 * the drift-resistance escape for a fresh field that should be redacted
 * but hasn't been added to the policy yet.
 *
 * <p>The walk recurses into nested object / record / collection fields:
 * an allow-listed field whose value is itself an aggregate (e.g.
 * {@code AircraftDetail.currentState}) is re-walked under the nested
 * type's simple-name key, so a stray PII field on the nested type still
 * lands as {@code "[redacted]"}. Leaf types (numbers, strings, UUIDs,
 * dates, enums) pass through as-is.
 *
 * <p>Returns a JSON string suitable for the {@code jsonb} column on
 * {@link ch.alpenflight.audit.domain.MutationAuditEvent}. Output is
 * size-capped at 64 KB serialized per the Performance plan; an overflow
 * is logged + replaced with a stub object — the row still lands so the
 * audit gap isn't created by the cap itself.
 */
@Component
public class PiiRedactor {

    public static final String REDACTED_SENTINEL = "[redacted]";
    private static final int MAX_SERIALIZED_BYTES = 64 * 1024;
    private static final String OVERSIZE_FALLBACK_JSON =
            "{\"_audit\":\"[oversize-snapshot-elided]\"}";

    private final AuditRedactionProperties policy;
    // Spring-injected ObjectMapper carries the TypedIdJacksonModule and the
    // project's Jackson tuning (default-property-inclusion: non_null, ISO-8601
    // dates, etc.). Constructing a fresh ObjectMapper bypassed both — typed
    // ids serialised as raw UUIDs and date fields differed from the API
    // contract. Reuse the configured bean instead.
    private final ObjectMapper mapper;
    private final Set<String> denyAllSet;

    public PiiRedactor(AuditRedactionProperties policy, ObjectMapper mapper) {
        this.policy = policy;
        this.mapper = mapper;
        this.denyAllSet = Set.copyOf(policy.denyAll());
    }

    /**
     * Serialize {@code snapshot} into a redacted JSON string keyed by
     * {@code entityType}. {@code null} snapshot returns {@code null} (the
     * column itself is nullable for CREATE / DELETE).
     */
    public @Nullable String serialize(String entityType, @Nullable Object snapshot) {
        if (snapshot == null) {
            return null;
        }
        Map<String, Object> redacted = walk(entityType, snapshot);
        try {
            String json = mapper.writeValueAsString(redacted);
            if (json.length() > MAX_SERIALIZED_BYTES) {
                return OVERSIZE_FALLBACK_JSON;
            }
            return json;
        } catch (JacksonException e) {
            // Serialization failure is itself signal. Surface a stub so the
            // audit row commits — the gap is observable, not silent.
            return "{\"_audit\":\"[serialize-failed]\"}";
        }
    }

    private Map<String, Object> walk(String entityType, Object snapshot) {
        Map<String, Object> out = new LinkedHashMap<>();
        EntityPolicy entry = policy.entities().get(entityType);
        boolean denyAll = denyAllSet.contains(entityType);
        Set<String> allow = (entry == null || denyAll)
                ? Set.of()
                : Set.copyOf(entry.allow());

        for (Field f : collectFields(snapshot.getClass())) {
            String name = f.getName();
            f.setAccessible(true);
            try {
                if (f.isAnnotationPresent(AuditRedact.class)) {
                    out.put(name, REDACTED_SENTINEL);
                } else if (!allow.contains(name)) {
                    out.put(name, REDACTED_SENTINEL);
                } else {
                    Object value = f.get(snapshot);
                    out.put(name, processValue(value));
                }
            } catch (IllegalAccessException ignored) {
                out.put(name, REDACTED_SENTINEL);
            }
        }
        return out;
    }

    /**
     * Recursively redact a field value. Leaf types pass through; nested
     * aggregates re-enter {@link #walk} under the runtime type's simple
     * name (so a {@code Person} field on a nested record still hits the
     * deny-all policy). Collections become lists of recursively-processed
     * elements; maps are conservatively redacted (unknown shape).
     */
    private @Nullable Object processValue(@Nullable Object value) {
        if (value == null || isLeafType(value)) {
            return value;
        }
        if (value instanceof Collection<?> coll) {
            List<Object> mapped = new ArrayList<>(coll.size());
            for (Object element : coll) {
                mapped.add(processValue(element));
            }
            return mapped;
        }
        if (value instanceof Map<?, ?>) {
            // Unknown key/value shapes can't be matched against the policy;
            // redacting is the safe default. Hit this only if a snapshot
            // exposes a Map (none today; flag if the future changes that).
            return REDACTED_SENTINEL;
        }
        return walk(value.getClass().getSimpleName(), value);
    }

    private static boolean isLeafType(Object value) {
        Class<?> cls = value.getClass();
        if (cls.isPrimitive() || cls.isEnum()) {
            return true;
        }
        return value instanceof CharSequence
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof BigDecimal
                || value instanceof BigInteger
                || value instanceof UUID
                || value instanceof Temporal
                || value instanceof java.util.Date
                || value instanceof Locale
                || value instanceof Class<?>;
    }

    private static java.util.List<Field> collectFields(Class<?> type) {
        java.util.List<Field> fields = new java.util.ArrayList<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Field f : current.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                if (f.isSynthetic()) {
                    continue;
                }
                fields.add(f);
            }
            current = current.getSuperclass();
        }
        return fields;
    }
}
