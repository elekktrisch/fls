package ch.alpenflight.planning.web;

import ch.alpenflight.audit.domain.ReadOnlyQuery;
import ch.alpenflight.planning.application.PlanningDayDtos.PlanningDayCreateRequest;
import ch.alpenflight.planning.application.PlanningDayDtos.PlanningDayDetail;
import ch.alpenflight.planning.application.PlanningDayDtos.PlanningDayPage;
import ch.alpenflight.planning.application.PlanningDayDtos.PlanningDayPageRequest;
import ch.alpenflight.planning.application.PlanningDayDtos.PlanningDayRuleRequest;
import ch.alpenflight.planning.application.PlanningDayDtos.PlanningDayUpdateRequest;
import ch.alpenflight.planning.application.PlanningDaysService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for the {@code PlanningDay} aggregate (J-6 T-04) — the CRUD seam.
 * The new kebab-case resource {@code /api/v1/planning-days} mirrors legacy
 * {@code /api/v1/planningdays} but adapts to AlpenFlight REST conventions (real
 * verbs, NOT the legacy {@code X-HTTP-Method-Override} tunnel — same call as the
 * J-5 reservations resource).
 *
 * <p><strong>Authz.</strong> Reads are open to any authenticated tenant member
 * ({@code @TenantId} scopes every read to the caller's club → cross-tenant 404).
 * Create is open to authenticated members; update + delete are gated in the
 * service to {@code CLUB_ADMINISTRATOR} OR the record's creator (legacy
 * {@code PlanningDayService.cs:407-425}) — a non-admin non-creator gets a 403.
 * The {@code canUpdate/canDeleteRecord} flags on every detail DTO surface the
 * same gate to the UI.
 *
 * <p>Each endpoint carries an explicit {@code @Operation(operationId=...)} so
 * orval generates stable named client methods (not positional {@code getN}) —
 * the J-6 orval-stability rider (J-3 T-10/T-11 lesson).
 */
@RestController
@RequestMapping(path = "/api/v1/planning-days", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Planning days",
        description = "Planning-day CRUD (tenant-scoped; 409 on duplicate club+date+location; "
                + "update/delete gated to admin-or-creator).")
public class PlanningDaysController {

    private final PlanningDaysService service;

    public PlanningDaysController(PlanningDaysService service) {
        this.service = service;
    }

    /**
     * SPA paged list — the legacy {@code POST .../page/{start}/{size}} shape with
     * a {@code PageableSearchFilter}-style body (the {@code Day.From} date
     * filter). Read-shaped POST (the filter body doesn't fit a GET query string),
     * so it carries {@link ReadOnlyQuery} to opt out of the mutating-verb audit
     * guard. Response is the camelCase {@code {items, pageStart, pageSize,
     * totalRows}} envelope; rows are future days sorted {@code planning_date asc}.
     */
    @Operation(operationId = "pagePlanningDays",
            summary = "Paged future planning-day list (SPA-compat page shape). Body carries the optional "
                    + "`Day.From` date filter (default today) + sorting.")
    @ApiResponse(responseCode = "200", description = "One page of detail rows + totalRows.")
    @PostMapping(path = "/page/{start}/{size}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @ReadOnlyQuery
    public PlanningDayPage pagePlanningDays(
            @PathVariable int start,
            @PathVariable int size,
            @RequestBody(required = false) @Nullable PlanningDayPageRequest request) {
        return service.page(start, size, request);
    }

    @Operation(operationId = "listFuturePlanningDays",
            summary = "Future planning days (planning_date ≥ today), tenant-scoped, sorted by date — "
                    + "the overview/future view.")
    @ApiResponse(responseCode = "200", description = "Future detail rows, sorted by planning_date asc.")
    @GetMapping("/overview/future")
    @PreAuthorize("isAuthenticated()")
    public List<PlanningDayDetail> listFuturePlanningDays() {
        return service.overviewFuture();
    }

    @Operation(operationId = "getPlanningDay",
            summary = "Read a single planning day by id.")
    @ApiResponse(responseCode = "200", description = "Planning-day detail projection.")
    @ApiResponse(responseCode = "404", description = "No active planning day with that id in the tenant.")
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public PlanningDayDetail getPlanningDay(@PathVariable UUID id) {
        return service.getPlanningDay(id);
    }

    @Operation(operationId = "createPlanningDay",
            summary = "Create a planning day. Duplicate (club, date, location) → 409.")
    @ApiResponse(responseCode = "201", description = "Created.")
    @ApiResponse(responseCode = "400", description = "Validation failed.")
    @ApiResponse(responseCode = "409", description = "A planning day already exists for that club+date+location.")
    @ApiResponse(responseCode = "422", description = "Planning date outside the sane range.")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PlanningDayDetail> createPlanningDay(
            @Valid @RequestBody PlanningDayCreateRequest req) {
        PlanningDayDetail created = service.createPlanningDay(req);
        return ResponseEntity
                .created(URI.create("/api/v1/planning-days/" + created.id()))
                .body(created);
    }

    /**
     * Bulk weekday-expansion (T-05; legacy {@code POST .../create/rule},
     * {@code PlanningDayCreatorRule}). Expands the inclusive date range to one
     * bare day (no crew) per selected weekday at the location, skipping
     * already-existing (club, date, location) days idempotently. Empty weekday
     * flags → empty result, no error; a range wider than the domain cap → 422.
     * Returns the list of days actually created (skipped days are not included).
     */
    @Operation(operationId = "bulkCreatePlanningDays",
            summary = "Bulk-create planning days by weekday over an inclusive date range. Empty weekday "
                    + "flags → empty result; existing (club, date, location) days are skipped idempotently.")
    @ApiResponse(responseCode = "201", description = "The list of days actually created (may be empty).")
    @ApiResponse(responseCode = "400", description = "Validation failed.")
    @ApiResponse(responseCode = "422", description = "The date range exceeds the sane span cap.")
    @PostMapping(path = "/create/rule", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<PlanningDayDetail>> bulkCreatePlanningDays(
            @Valid @RequestBody PlanningDayRuleRequest req) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
                .body(service.bulkCreatePlanningDays(req));
    }

    @Operation(operationId = "updatePlanningDay",
            summary = "Update a planning day (admin-or-creator). Duplicate (club, date, location) → 409.")
    @ApiResponse(responseCode = "200", description = "Updated.")
    @ApiResponse(responseCode = "403", description = "Caller is neither a club admin nor the record creator.")
    @ApiResponse(responseCode = "404", description = "No active planning day with that id in the tenant.")
    @ApiResponse(responseCode = "409", description = "Another day already occupies that club+date+location.")
    @ApiResponse(responseCode = "422", description = "Planning date outside the sane range.")
    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public PlanningDayDetail updatePlanningDay(
            @PathVariable UUID id,
            @Valid @RequestBody PlanningDayUpdateRequest req) {
        return service.updatePlanningDay(id, req);
    }

    @Operation(operationId = "deletePlanningDay",
            summary = "Soft-delete a planning day (admin-or-creator; cascades its crew assignments).")
    @ApiResponse(responseCode = "204", description = "Deleted.")
    @ApiResponse(responseCode = "403", description = "Caller is neither a club admin nor the record creator.")
    @ApiResponse(responseCode = "404", description = "No active planning day with that id in the tenant.")
    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> deletePlanningDay(@PathVariable UUID id) {
        service.deletePlanningDay(id);
        return ResponseEntity.noContent().build();
    }
}
