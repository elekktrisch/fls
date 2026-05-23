package ch.alpenflight.audit.web;

import ch.alpenflight.audit.domain.AuditAction;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Wire-shapes for {@code GET /api/v1/admin/audit-events}. Two nested
 * records: a row projection that lands in the JSON response and a query
 * record that captures the filter parameters cleanly so the controller
 * surface stays narrow.
 *
 * <p>Snapshots are surfaced verbatim as JSON strings — the redactor
 * already serialised them to {@code jsonb}; re-parsing into Jackson
 * trees would double-quote everything. S-056 parses them client-side.
 */
final class AuditEventDtos {

    private AuditEventDtos() {}

    record AuditEventRow(UUID id,
                         Instant occurredAt,
                         @Nullable UUID actorUserId,
                         @Nullable UUID actorKeycloakSub,
                         @Nullable UUID tenantClubId,
                         AuditAction action,
                         String targetEntityType,
                         @Nullable UUID targetEntityId,
                         @Nullable String requestId,
                         @Nullable String beforeState,
                         @Nullable String afterState,
                         boolean failed,
                         boolean systemActor,
                         @Nullable Integer httpStatus,
                         @Nullable String failureReason) {
    }
}
