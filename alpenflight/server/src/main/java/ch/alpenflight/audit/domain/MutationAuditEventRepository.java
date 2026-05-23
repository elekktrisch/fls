package ch.alpenflight.audit.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Persistence port for {@link MutationAuditEvent}. Implemented in
 * {@code audit.infra} by Spring Data JPA. Reads pass through Hibernate's
 * {@code @TenantId} filter so {@code GET /api/v1/admin/audit-events} is
 * structurally scoped to the caller's tenant — no per-query
 * {@code where tenant_club_id = ?} stanza needed.
 *
 * <p>{@link #append} is the only write surface; nothing in this port
 * exposes update or delete because the table is append-only by design.
 * Structural append-only via DB-role grant is deferred to S-160; today's
 * guard is convention (only this port writes; no UPDATE/DELETE methods
 * exist).
 */
public interface MutationAuditEventRepository {

    /** Persist a new audit row. Returns the managed entity (with id assigned). */
    MutationAuditEvent append(MutationAuditEvent event);

    /** Lookup a single event by id within the caller's tenant scope. */
    Optional<MutationAuditEvent> findById(UUID id);

    /**
     * Paginated list filtered by optional dimensions. All filters are
     * additive; passing {@code null} skips the dimension. Ordering is
     * {@code occurred_at DESC} (the {@code (tenant_club_id, occurred_at DESC)}
     * index serves this). {@code pageSize} is capped at 200 per the
     * Performance plan; the caller's input is clamped, not rejected.
     */
    List<MutationAuditEvent> findPage(@Nullable Instant occurredFrom,
                                      @Nullable Instant occurredTo,
                                      @Nullable AuditAction action,
                                      @Nullable String targetEntityType,
                                      @Nullable UUID actorUserId,
                                      int pageSize,
                                      int pageOffset);
}
