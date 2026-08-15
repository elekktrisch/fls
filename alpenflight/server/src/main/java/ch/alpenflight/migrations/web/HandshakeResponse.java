package ch.alpenflight.migrations.web;

import ch.alpenflight.migrations.application.MigrationUploadView;
import ch.alpenflight.platform.id.MigrationUploadId;
import java.time.Instant;

public record HandshakeResponse(MigrationUploadId uploadId, String publicKeyPem, Instant expiresAt) {

    public static HandshakeResponse of(MigrationUploadView view) {
        return new HandshakeResponse(
                MigrationUploadId.of(view.uploadId()),
                view.publicKeyPem(),
                view.expiresAt());
    }
}
