package ch.alpenflight.audit.domain;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record AuditedTarget(String entityType,
                            @Nullable UUID entityId,
                            @Nullable Object before,
                            @Nullable Object after) {

    public AuditedTarget {
        if (entityType == null || entityType.isBlank()) {
            throw new IllegalArgumentException("entityType must not be blank");
        }
    }

    public static AuditedTarget created(String entityType, UUID entityId, Object after) {
        return new AuditedTarget(entityType, entityId, null, after);
    }

    public static AuditedTarget updated(String entityType, UUID entityId, Object before, Object after) {
        return new AuditedTarget(entityType, entityId, before, after);
    }

    public static AuditedTarget deleted(String entityType, UUID entityId, Object before) {
        return new AuditedTarget(entityType, entityId, before, null);
    }
}
