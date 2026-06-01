package ch.alpenflight.migrations.application;

import ch.alpenflight.migrations.domain.BundleIngestErrorCode;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Audit snapshot record for the three {@code MIGRATION_INGEST_*} actions.
 * Tiny by design — ids + ints + bounded enum codes only; never display
 * names, never PII from the bundle. The {@code audit.redaction.entities.
 * MigrationIngestAuditSnapshot.allow} list in {@code application.yml} is
 * the structural redaction gate: a field added here without an allow-list
 * update surfaces as {@code [redacted]} on the audit row.
 */
public record MigrationIngestAuditSnapshot(
        UUID uploadId,
        @Nullable UUID deploymentId,
        @Nullable Integer clubCount,
        @Nullable BundleIngestErrorCode errorCode,
        @Nullable String phase) {

    public static final String AUDIT_ENTITY_TYPE = "MigrationIngestAuditSnapshot";

    public static MigrationIngestAuditSnapshot started(UUID uploadId) {
        return new MigrationIngestAuditSnapshot(uploadId, null, null, null, null);
    }

    public static MigrationIngestAuditSnapshot completed(UUID uploadId,
                                                         UUID deploymentId,
                                                         int clubCount) {
        return new MigrationIngestAuditSnapshot(uploadId, deploymentId, clubCount, null, null);
    }

    public static MigrationIngestAuditSnapshot failed(UUID uploadId,
                                                      BundleIngestErrorCode errorCode,
                                                      String phase) {
        return new MigrationIngestAuditSnapshot(uploadId, null, null, errorCode, phase);
    }
}
