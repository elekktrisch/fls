package ch.alpenflight.audit.domain;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a service is recording for the audit trail.
 *
 * @param entityType  short logical name of the target aggregate
 *                    ({@code "Club"}, {@code "Location"}). Free-form string
 *                    rather than a Class so the value survives entity
 *                    rename / package move without rewriting historical
 *                    audit rows. S-056 filters on it as a dropdown.
 * @param entityId    the target row's id. {@code null} is reserved for
 *                    {@link AuditAction#BULK_IMPORT} where the summary
 *                    {@code after_state} carries the per-row identifiers.
 * @param before      the entity snapshot before the mutation; {@code null}
 *                    for {@link AuditAction#CREATE}.
 * @param after       the entity snapshot after the mutation; {@code null}
 *                    for {@link AuditAction#DELETE}.
 *
 * <p>{@code before} / {@code after} are the raw entity references handed to
 * the redacting serializer. Callers do not pre-serialize.
 */
public record AuditedTarget(String entityType,
                            @Nullable UUID entityId,
                            @Nullable Object before,
                            @Nullable Object after) {

    public AuditedTarget {
        if (entityType == null || entityType.isBlank()) {
            throw new IllegalArgumentException("entityType must not be blank");
        }
    }

    /** Convenience for CREATE: only the after-snapshot is meaningful. */
    public static AuditedTarget created(String entityType, UUID entityId, Object after) {
        return new AuditedTarget(entityType, entityId, null, after);
    }

    /** Convenience for UPDATE: both snapshots populated. */
    public static AuditedTarget updated(String entityType, UUID entityId, Object before, Object after) {
        return new AuditedTarget(entityType, entityId, before, after);
    }

    /** Convenience for DELETE: only the before-snapshot is meaningful. */
    public static AuditedTarget deleted(String entityType, UUID entityId, Object before) {
        return new AuditedTarget(entityType, entityId, before, null);
    }
}
