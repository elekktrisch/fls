package ch.alpenflight.migrations.application;

import ch.alpenflight.migrations.domain.MigrationUploadState;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record MigrationUploadAuditSnapshot(UUID uploadId,
                                           MigrationUploadState state,
                                           Instant expiresAt,
                                           @Nullable String privateKeyCiphertextSummary) {

    public static final String AUDIT_ENTITY_TYPE =
            MigrationUploadAuditSnapshot.class.getSimpleName();

    public static MigrationUploadAuditSnapshot inFlight(UUID uploadId,
                                                        MigrationUploadState state,
                                                        Instant expiresAt,
                                                        int ciphertextLength) {
        return new MigrationUploadAuditSnapshot(uploadId, state, expiresAt,
                "<bytes:" + ciphertextLength + ">");
    }

    public static MigrationUploadAuditSnapshot wiped(UUID uploadId,
                                                     MigrationUploadState state,
                                                     Instant expiresAt) {
        return new MigrationUploadAuditSnapshot(uploadId, state, expiresAt, null);
    }
}
