package ch.alpenflight.audit.web;

import ch.alpenflight.audit.application.AuditEventDtos.AuditEventPage;
import ch.alpenflight.audit.application.AuditEventDtos.AuditEventQuery;
import ch.alpenflight.audit.application.AuditQueryService;
import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.MutationAuditEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
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

    AuditAdminController(AuditQueryService queryService) {
        this.queryService = queryService;
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
}
