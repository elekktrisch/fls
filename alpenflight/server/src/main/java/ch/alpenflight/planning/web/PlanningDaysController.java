package ch.alpenflight.planning.web;

import ch.alpenflight.audit.domain.ReadOnlyQuery;
import ch.alpenflight.planning.application.PlanningDayDtos.PlanningDayCreateRequest;
import ch.alpenflight.planning.application.PlanningDayDtos.PlanningDayDetail;
import ch.alpenflight.planning.application.PlanningDayDtos.PlanningDayPage;
import ch.alpenflight.planning.application.PlanningDayDtos.PlanningDayPageRequest;
import ch.alpenflight.planning.application.PlanningDayDtos.PlanningDayRuleRequest;
import ch.alpenflight.planning.application.PlanningDayDtos.PlanningDayUpdateRequest;
import ch.alpenflight.planning.application.PlanningDayDtos.PlanningDayValidateRequest;
import ch.alpenflight.planning.application.PlanningDayDtos.PlanningDayValidationResult;
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

    @Operation(operationId = "validatePlanningDayUniqueness",
            summary = "Pre-check a candidate (date, location) for a duplicate planning day (non-mutating). "
                    + "200 with {valid,field,message} — surfaces the save-path 409 inline before save.")
    @ApiResponse(responseCode = "200", description = "Validation outcome (valid flag + offending field/message).")
    @PostMapping(path = "/validate", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @ReadOnlyQuery
    public PlanningDayValidationResult validatePlanningDayUniqueness(
            @Valid @RequestBody PlanningDayValidateRequest req) {
        return service.validateUniqueness(req);
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
