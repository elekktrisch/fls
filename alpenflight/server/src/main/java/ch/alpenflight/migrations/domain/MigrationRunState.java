package ch.alpenflight.migrations.domain;

/**
 * S-141 ingest-run lifecycle. Stored as {@code @Enumerated(STRING)} in
 * {@code t_migration_run.state}. Per ADR 0022 directive 2 the enum lives
 * in Java — no Postgres CHECK constraint pins the legal set.
 *
 * <p>Single forward path through the FSM ends in either {@link #COMPLETED}
 * or {@link #FAILED}. Each non-terminal state corresponds to a phase the
 * status endpoint surfaces to the SPA.
 */
public enum MigrationRunState {

    /** Header parsed, RSA private key unwrapped, body decrypting. */
    DECRYPTING,

    /** Manifest parsed; Deployment + Clubs being created via S-138. */
    PROVISIONING,

    /** Per-entity NDJSON streams flowing through {@code Mapper.readEntity}. */
    INGESTING,

    /** All entity streams drained; finalising COPY tables + audit emission. */
    COMPLETING,

    /** Terminal: ingest succeeded; {@code MigrationUpload.markConsumed} ran. */
    COMPLETED,

    /** Terminal: any phase rolled back; {@code MigrationUpload.markFailed} ran. */
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
