package ch.alpenflight.audit.application;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Operator-managed redaction policy. Loaded from
 * {@code application*.yml} under {@code audit.redaction}.
 *
 * <p>Default-deny serialization: only fields explicitly allowed (per-entity
 * allow-list) are emitted; everything else surfaces as
 * {@code "[redacted]"}. Per-field {@link ch.alpenflight.audit.domain.AuditRedact}
 * annotations also redact regardless of the policy, so a brand-new field
 * carrying PII can ship the annotation without an immediate yaml edit.
 *
 * <p>{@link #denyAll} lists logical entity types ({@code Person}, etc.) whose
 * every field is redacted unconditionally even when the per-entity policy
 * would otherwise allow it — the central kill-switch for known-PII entities.
 *
 * <p>Map keys are the {@link ch.alpenflight.audit.domain.AuditedTarget#entityType()
 * AuditedTarget.entityType} strings, never Java class names — the policy
 * survives entity rename / package move.
 */
@ConfigurationProperties("audit.redaction")
public record AuditRedactionProperties(Map<String, EntityPolicy> entities,
                                       List<String> denyAll) {

    public AuditRedactionProperties {
        entities = entities == null ? Map.of() : Map.copyOf(entities);
        denyAll = denyAll == null ? List.of() : List.copyOf(denyAll);
    }

    /**
     * Per-entity policy.
     *
     * @param allow  field names emitted verbatim. Anything not on the list is
     *               redacted. Empty list = redact everything.
     */
    public record EntityPolicy(List<String> allow) {
        public EntityPolicy {
            allow = allow == null ? List.of() : List.copyOf(allow);
        }
    }
}
