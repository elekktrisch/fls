package ch.alpenflight.migrations.application;

import ch.alpenflight.migrations.domain.MigrationUpload;
import java.time.Instant;
import java.util.UUID;

public record MigrationUploadView(UUID uploadId, String publicKeyPem, Instant expiresAt) {

    public static MigrationUploadView of(MigrationUpload row) {
        return new MigrationUploadView(row.getRawId(), row.getPublicKeyPem(), row.getExpiresAt());
    }
}
