package ch.alpenflight.audit.domain;

/**
 * The shape of mutation an audit row records. Stored as
 * {@code @Enumerated(STRING)} in {@code mutation_audit_event.action} — per
 * ADR 0022 directive 2 the enum is pinned in Java, never as a Postgres
 * CHECK constraint or DB enum type, so adding a value never requires a
 * migration in lock-step.
 *
 * <ul>
 *   <li>{@link #CREATE} — a new aggregate root was persisted. {@code before_state}
 *       is null; {@code after_state} carries the redacted snapshot.</li>
 *   <li>{@link #UPDATE} — an existing aggregate was mutated. Both snapshots
 *       are populated so {@code S-056} can compute the diff at read time.</li>
 *   <li>{@link #DELETE} — soft- or hard-delete. {@code after_state} is null;
 *       {@code before_state} carries the row as it existed.</li>
 *   <li>{@link #STATE_TRANSITION} — a state-machine transition that isn't
 *       captured cleanly as UPDATE (e.g. invoice booking). Reserved for the
 *       state-machine stories.</li>
 *   <li>{@link #BULK_IMPORT} — the cutover importer and similar bulk paths
 *       emit one event per HTTP request with a summary {@code after_state}
 *       (count + first/last id) rather than per-row, per the refinement's
 *       payload-ceiling decision.</li>
 * </ul>
 */
public enum AuditAction {
    CREATE,
    UPDATE,
    DELETE,
    STATE_TRANSITION,
    BULK_IMPORT,
    /** Search/lookup that returned ≥ 1 row. The negative response is itself
     *  information disclosure on cross-tenant directory queries, so hits and
     *  misses are recorded as distinct actions for forensics filtering. */
    LOOKUP_HIT,
    /** Search/lookup that returned 0 rows. Audited so repeated misses for
     *  the same key surface as a probing signal in S-056. */
    LOOKUP_MISS
}
