package ch.alpenflight.audit.domain;

/**
 * Separates rows the running application appended from rows an import
 * planted. Stored as {@code @Enumerated(STRING)} in
 * {@code t_mutation_audit_event.actor_kind} — per ADR 0022 directive 2 the
 * enum is pinned in Java, never as a Postgres CHECK constraint or DB enum
 * type.
 *
 * <p>It is NOT what classifies a human principal against an anonymous or
 * scheduled write: that is the {@code system_actor} boolean, which is the
 * field the {@code /system/logs} projection
 * ({@code AuditEventDtos.AuditEventRow}) carries and the viewer renders.
 * {@code actor_kind} is absent from that projection by design, so any change
 * to how it is populated is invisible to operators unless the projection and
 * the viewer move with it.
 *
 * <ul>
 *   <li>{@link #NORMAL} — every row {@code MutationAuditEventListener}
 *       appends, whether the actor was an authenticated principal or an
 *       anonymous public-flow / scheduled write.</li>
 *   <li>{@link #SYSTEM} — no writer. {@code system_actor} already carries
 *       the distinction; populating this would need the projection + viewer
 *       change described above to mean anything.</li>
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
