package ch.alpenflight.audit.domain;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Field-level redaction marker. The default-deny audit serializer emits
 * {@code "[redacted]"} for any field carrying this annotation, regardless
 * of the central {@code audit-redaction.yml} policy. Sits next to the
 * field so a future field addition that should be redacted gets the
 * annotation in the same diff (drift-resistance — code review surfaces
 * the omission immediately).
 *
 * <p>Use for fields whose redaction is intrinsic to the value (PII, secrets);
 * use the central policy for cross-cutting bulk rules (all Person fields
 * except id).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface AuditRedact {
}
