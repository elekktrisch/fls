package ch.alpenflight.migrations.domain;

/**
 * S-140 migration-upload lifecycle. Stored as {@code @Enumerated(STRING)}
 * in {@code t_migration_upload.state}. Per ADR 0022 directive 2 the enum
 * lives in Java — no Postgres CHECK constraint pins the legal set.
 *
 * <p>Boundary contract with S-141 (decrypt + ingest):
 *
 * <ul>
 *   <li>S-140 owns transitions out of {@link #AWAITING_UPLOAD} to
 *       {@link #SUPERSEDED} (subsequent /handshake from same user) and
 *       {@link #EXPIRED} (hourly TTL sweep).</li>
 *   <li>S-141 owns transitions out of {@link #AWAITING_UPLOAD} to
 *       {@link #CONSUMED} (ingest success) and {@link #FAILED} (ingest
 *       error).</li>
 * </ul>
 *
 * <p>All four terminal states wipe {@code private_key_ciphertext} to NULL.
 */
public enum MigrationUploadState {

    /** Handshake row freshly issued, holding the wrapped private key until ingest consumes it. */
    AWAITING_UPLOAD,

    /** A later handshake from the same user invalidated this row; private key wiped. */
    SUPERSEDED,

    /** Hourly TTL sweep advanced this row past its {@code expires_at}; private key wiped. */
    EXPIRED,

    /** S-141 ingest pipeline rejected the bundle; private key wiped. */
    FAILED,

    /** S-141 ingest pipeline successfully unwrapped + applied the bundle; private key wiped. */
    CONSUMED;

    /** {@code true} when the row still holds the wrapped private key. */
    public boolean isInFlight() {
        return this == AWAITING_UPLOAD;
    }
}
