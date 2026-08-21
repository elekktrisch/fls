package ch.alpenflight.audit.web;

import ch.alpenflight.audit.application.AuditEventDtos.AuditEventPage;
import ch.alpenflight.audit.application.AuditEventDtos.AuditEventQuery;
import ch.alpenflight.audit.application.AuditQueryService;
import ch.alpenflight.audit.application.ClientIpRedaction;
import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.MutationAuditEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/admin/audit-events", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Audit Events", description = "Mutation-audit trail (per-tenant, admin-only).")
@PreAuthorize("hasAnyRole('CLUB_ADMINISTRATOR', 'SYSTEM_ADMINISTRATOR')")
class AuditAdminController {

    private static final int DEFAULT_PAGE_SIZE = 50;

    private final AuditQueryService queryService;
    private final ClientIpRedaction clientIpRedaction;

    AuditAdminController(AuditQueryService queryService, ClientIpRedaction clientIpRedaction) {
        this.queryService = queryService;
        this.clientIpRedaction = clientIpRedaction;
    }

    @Operation(operationId = "listAuditEvents",
            summary = "List audit events for the caller's tenant. All filters are optional.")
    @ApiResponse(responseCode = "200", description = "Page of audit-event projections + cursor metadata.")
    @GetMapping
    AuditEventPage listAuditEvents(
            @RequestParam(required = false) @Nullable Instant occurredFrom,
            @RequestParam(required = false) @Nullable Instant occurredTo,
            @RequestParam(required = false) @Nullable AuditAction action,
            @RequestParam(required = false) @Nullable String targetEntityType,
            @RequestParam(required = false) @Nullable UUID actorUserId,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_PAGE_SIZE) int pageSize,
            @RequestParam(required = false, defaultValue = "0") int pageOffset) {
        return queryService.findPage(new AuditEventQuery(
                occurredFrom, occurredTo, action, targetEntityType,
                actorUserId, pageSize, pageOffset));
    }

    @Operation(operationId = "redactAuditEventClientIp",
            summary = "Redact the client IP of one audit event of the caller's tenant. "
                    + "The audit row stays; only the client IP goes.")
    @ApiResponse(responseCode = "204",
            description = "The client IP is gone. Every other cell of the row is unchanged.")
    @ApiResponse(responseCode = "404",
            description = "The caller's tenant holds no audit event with this id.")
    @DeleteMapping("/{auditEventId}/client-ip")
    ResponseEntity<Void> redactAuditEventClientIp(@PathVariable UUID auditEventId) {
        return clientIpRedaction.redactOneClientIpAheadOfTheRetentionWindow(auditEventId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
