package ch.alpenflight.migrations.web;

import ch.alpenflight.migrations.application.MigrationUploadView;
import ch.alpenflight.platform.id.MigrationUploadId;
import java.time.Instant;

/**
 * Wire DTO for the {@code /handshake} + {@code /handshake/current}
 * endpoints. Exactly three fields by the security plan — pinned in code
 * (record) and asserted in the integration test.
 */
public record HandshakeResponse(MigrationUploadId uploadId, String publicKeyPem, Instant expiresAt) {

    public static HandshakeResponse of(MigrationUploadView view) {
        return new HandshakeResponse(
                MigrationUploadId.of(view.uploadId()),
                view.publicKeyPem(),
                view.expiresAt());
    }
}
