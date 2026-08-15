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

@Component
public class PiiRedactor {

    public static final String REDACTED_SENTINEL = "[redacted]";
    private static final int MAX_SERIALIZED_BYTES = 64 * 1024;
    private static final String OVERSIZE_FALLBACK_JSON =
            "{\"_audit\":\"[oversize-snapshot-elided]\"}";

    private final AuditRedactionProperties policy;
    private final ObjectMapper mapper;
    private final Set<String> denyAllSet;

    public PiiRedactor(AuditRedactionProperties policy, ObjectMapper mapper) {
        this.policy = policy;
        this.mapper = mapper;
        this.denyAllSet = Set.copyOf(policy.denyAll());
    }

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
