package ch.alpenflight.migration.bundle;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One non-fatal note emitted by the producer when it omits a legacy row that
 * cannot satisfy a new-stack invariant (e.g. an Aircraft whose owner resolves
 * to no managing Club). Bundle-local on purpose: the parity harness lives in
 * {@code migration-bundle}, which must not depend on {@code alpenflight/server}
 * (the dependency runs server → bundle), so the harness cannot reference the
 * server-side {@code MigrationRunWarning}. This record is the bundle-side
 * analog the in-process {@code ProducerHarness} collects and
 * {@link ProducerDropReconciliation} folds into the row-count equality.
 *
 * <p>{@code clubId} / {@code legacyGuid} are captured when the warning is
 * row-scoped. Per the Security plan, {@code detail} may carry a legacy column
 * value for forensic context but never PII pulled straight off the row.
 */
public record ProducerDropWarning(
        String code,
        EntityType entityType,
        @Nullable UUID clubId,
        @Nullable UUID legacyGuid,
        String detail) {

    public ProducerDropWarning {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        if (entityType == null) {
            throw new IllegalArgumentException("entityType must not be null");
        }
        if (detail == null) {
            detail = "";
        }
    }
}
