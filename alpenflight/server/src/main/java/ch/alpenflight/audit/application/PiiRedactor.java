package ch.alpenflight.audit.application;

import ch.alpenflight.audit.application.AuditRedactionProperties.EntityPolicy;
import ch.alpenflight.audit.domain.AuditRedact;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Default-deny serializer that walks the snapshot reflectively and emits
 * only fields the {@link AuditRedactionProperties} policy explicitly
 * allows. Anything else lands as the literal {@code "[redacted]"}
 * sentinel (not omitted — absence is unambiguous).
 *
 * <p>Field-level {@link AuditRedact} annotations redact unconditionally,
 * even when the central policy would otherwise allow the field. That's
 * the drift-resistance escape for a fresh entity field that should be
 * redacted but hasn't been added to the yaml yet.
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
    private final ObjectMapper mapper = new ObjectMapper();
    private final Set<String> denyAllSet;

    public PiiRedactor(AuditRedactionProperties policy) {
        this.policy = policy;
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
        } catch (JsonProcessingException e) {
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
                    out.put(name, value);
                }
            } catch (IllegalAccessException ignored) {
                out.put(name, REDACTED_SENTINEL);
            }
        }
        return out;
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
