package ch.alpenflight.migrations.domain;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One row of the {@code t_migration_run.warnings} jsonb array — non-fatal
 * notes from the ingest pipeline that S-187a's parity-run report consumes.
 * Codes are bounded (the producer + consumer agree on the set);
 * {@code legacyGuid} is captured when the warning is row-scoped.
 *
 * <p>Per the Security plan: the jsonb column is {@code @AuditRedact} on
 * the aggregate field. Detail strings may capture a legacy column value
 * for forensic context; never PII direct from the row.
 */
public record MigrationRunWarning(
        String code,
        String entityType,
        @Nullable UUID clubId,
        @Nullable UUID legacyGuid,
        String detail) {

    public MigrationRunWarning {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        if (entityType == null || entityType.isBlank()) {
            throw new IllegalArgumentException("entityType must not be blank");
        }
        if (detail == null) {
            detail = "";
        }
    }
}
