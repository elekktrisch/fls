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

    /**
     * Drop a still-managed entity from the persistence context. Used by
     * the handshake-race recovery path so the loser's locally-built row
     * is not flushed back to the DB after the winner's INSERT has
     * already taken the partial-UNIQUE slot. Implemented in
     * {@code infra} via {@code EntityManager.detach} (Spring Data
     * doesn't expose this on {@code JpaRepository}); the application
     * layer never imports {@code EntityManager} directly.
     */
    void detachRow(MigrationUpload row);
}
