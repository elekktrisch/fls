package ch.alpenflight.audit.domain;

/**
 * Classifies the actor behind an audit row. Stored as
 * {@code @Enumerated(STRING)} in {@code t_mutation_audit_event.actor_kind}
 * — per ADR 0022 directive 2 the enum is pinned in Java, never as a
 * Postgres CHECK constraint or DB enum type.
 *
 * <ul>
 *   <li>{@link #NORMAL} — the default. An authenticated user via the JWT
 *       chain; {@code actor_user_id} + {@code actor_keycloak_sub} both
 *       populated.</li>
 *   <li>{@link #SYSTEM} — a system actor (cron, OGN ingestion, scheduled
 *       jobs); {@code actor_user_id} + {@code actor_keycloak_sub} NULL,
 *       {@code system_actor=true}.</li>
 *   <li>{@link #LEGACY_MIGRATED} — written only by the S-186 cutover
 *       importer. {@code actor_user_id} holds either the resolved real
 *       {@code User.id} (UserName matched a real Users row in the bundle)
 *       or a bundle-local synthetic UUID v7 (one per distinct legacy
 *       UserName); {@code actor_keycloak_sub} is NULL;
 *       {@code legacy_actor_user_id} carries the raw legacy UserName;
 *       {@code legacy_int_id} carries the legacy AuditLogs.AuditLogId for
 *       forensic round-trip.</li>
 * </ul>
 */
public enum AuditActorKind {
    NORMAL,
    SYSTEM,
    LEGACY_MIGRATED
}
