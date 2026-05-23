package ch.alpenflight.audit.web;

import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.MutationAuditEvent;
import ch.alpenflight.audit.domain.MutationAuditEventRepository;
import ch.alpenflight.audit.web.AuditEventDtos.AuditEventRow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin read surface for the mutation-audit trail. Per-tenant scoping is
 * structural — Hibernate's {@code @TenantId} discriminator on
 * {@link MutationAuditEvent} filters every query to the caller's tenant.
 * Cross-tenant access (a SYSTEM_ADMINISTRATOR auditing another club) is
 * deferred to S-023's {@code UnscopedTenantContext}; until that lands,
 * SYSTEM_ADMINISTRATOR sees only their own JWT-claimed tenant, identical
 * to CLUB_ADMINISTRATOR.
 *
 * <p>S-056 (admin UI) consumes this endpoint through the generated TS
 * client.
 */
@RestController
@RequestMapping(path = "/api/v1/admin/audit-events", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Audit Events", description = "Mutation-audit trail (per-tenant, admin-only).")
@PreAuthorize("hasAnyRole('CLUB_ADMINISTRATOR', 'SYSTEM_ADMINISTRATOR')")
class AuditAdminController {

    private static final int DEFAULT_PAGE_SIZE = 50;

    private final MutationAuditEventRepository repository;

    AuditAdminController(MutationAuditEventRepository repository) {
        this.repository = repository;
    }

    @Operation(operationId = "listAuditEvents",
            summary = "List audit events for the caller's tenant. All filters are optional.")
    @ApiResponse(responseCode = "200", description = "Page of audit-event projections.")
    @GetMapping
    List<AuditEventRow> listAuditEvents(
            @RequestParam(required = false) @Nullable Instant occurredFrom,
            @RequestParam(required = false) @Nullable Instant occurredTo,
            @RequestParam(required = false) @Nullable AuditAction action,
            @RequestParam(required = false) @Nullable String targetEntityType,
            @RequestParam(required = false) @Nullable UUID actorUserId,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_PAGE_SIZE) int pageSize,
            @RequestParam(required = false, defaultValue = "0") int pageOffset) {
        return repository.findPage(occurredFrom, occurredTo, action, targetEntityType,
                        actorUserId, pageSize, pageOffset)
                .stream()
                .map(AuditAdminController::toRow)
                .toList();
    }

    private static AuditEventRow toRow(MutationAuditEvent e) {
        UUID id = e.getId();
        Instant occurredAt = e.getOccurredAt();
        AuditAction action = e.getAction();
        String type = e.getTargetEntityType();
        if (id == null || occurredAt == null || action == null || type == null) {
            throw new IllegalStateException(
                    "Loaded audit row is missing required fields (id/occurredAt/action/type)");
        }
        Short status = e.getHttpStatus();
        return new AuditEventRow(
                id,
                occurredAt,
                e.getActorUserId(),
                e.getActorKeycloakSub(),
                e.getTenantClubId(),
                action,
                type,
                e.getTargetEntityId(),
                e.getRequestId(),
                e.getBeforeState(),
                e.getAfterState(),
                e.isFailed(),
                e.isSystemActor(),
                status == null ? null : status.intValue(),
                e.getFailureReason());
    }
}
