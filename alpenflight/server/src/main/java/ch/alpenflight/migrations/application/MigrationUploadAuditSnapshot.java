package ch.alpenflight.migrations.application;

import ch.alpenflight.migrations.domain.MigrationUploadState;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Snapshot record handed to the audit-trail emitter for
 * {@link ch.alpenflight.audit.domain.AuditAction#MIGRATION_HANDSHAKE_ISSUED}
 * / {@code SUPERSEDED} / {@code EXPIRED} events. Tiny by design: state +
 * expiry + a byte-length placeholder for the wrapped private key. NEVER
 * the raw ciphertext bytes.
 *
 * <p>The audit redactor allow-lists {@code state}, {@code expiresAt},
 * {@code privateKeyCiphertextSummary} (entity type
 * {@code MigrationUploadAuditSnapshot}) in {@code application.yml}. Any
 * field added here without a matching allow-list entry surfaces as
 * {@code "[redacted]"} at runtime, not as raw output.
 */
public record MigrationUploadAuditSnapshot(UUID uploadId,
                                           MigrationUploadState state,
                                           Instant expiresAt,
                                           @Nullable String privateKeyCiphertextSummary) {

    public static MigrationUploadAuditSnapshot inFlight(UUID uploadId,
                                                        MigrationUploadState state,
                                                        Instant expiresAt,
                                                        int ciphertextLength) {
        return new MigrationUploadAuditSnapshot(uploadId, state, expiresAt,
                "<bytes:" + ciphertextLength + ">");
    }

    /** Snapshot after a terminal transition wiped the private-key bytes. */
    public static MigrationUploadAuditSnapshot wiped(UUID uploadId,
                                                     MigrationUploadState state,
                                                     Instant expiresAt) {
        return new MigrationUploadAuditSnapshot(uploadId, state, expiresAt, null);
    }
}
