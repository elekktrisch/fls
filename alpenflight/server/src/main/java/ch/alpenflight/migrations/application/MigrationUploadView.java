package ch.alpenflight.migrations.application;

import ch.alpenflight.migrations.domain.MigrationUpload;
import java.time.Instant;
import java.util.UUID;

/**
 * Application-layer view of a {@link MigrationUpload} row, sans private
 * key. The web layer maps this to {@code HandshakeResponse}; the SPA
 * mount-restore path and the issue path both go through the view so the
 * web boundary never sees the wrapped ciphertext.
 */
public record MigrationUploadView(UUID uploadId, String publicKeyPem, Instant expiresAt) {

    public static MigrationUploadView of(MigrationUpload row) {
        return new MigrationUploadView(row.getRawId(), row.getPublicKeyPem(), row.getExpiresAt());
    }
}
