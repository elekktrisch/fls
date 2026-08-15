package ch.alpenflight.migrations.application;

import ch.alpenflight.migrations.domain.BundleIngestErrorCode;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

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
