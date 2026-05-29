package ch.alpenflight.migrations.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain port for {@link MigrationUpload} persistence. Implemented by
 * {@code ch.alpenflight.migrations.infra.JpaMigrationUploadRepository}.
 *
 * <p>No tenant scope: handshake rows are pre-tenant by design. Lookups
 * are keyed on {@code user_id} (one in-flight row per user, enforced
 * structurally by the partial UNIQUE) and the cleanup sweep walks rows
 * by {@code (state, expires_at)}.
 */
public interface MigrationUploadRepository {

    Optional<MigrationUpload> findAwaitingByUser(UUID userId);

    Optional<MigrationUpload> findById(UUID id);

    /**
     * Walk every {@link MigrationUploadState#AWAITING_UPLOAD} row whose
     * {@code expires_at} is past {@code now}. Used by the hourly sweep;
     * the caller flips state + wipes the private-key bytes per row and
     * commits in one transaction.
     */
    List<MigrationUpload> findExpired(Instant now);

    MigrationUpload save(MigrationUpload row);

    void flush();
}
