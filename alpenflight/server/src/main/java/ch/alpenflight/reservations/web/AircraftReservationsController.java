package ch.alpenflight.reservations.web;

import ch.alpenflight.audit.domain.ReadOnlyQuery;
import ch.alpenflight.platform.tenancy.UserPrincipalLookup;
import ch.alpenflight.reservations.application.AircraftReservationDtos.AircraftReservationCreateRequest;
import ch.alpenflight.reservations.application.AircraftReservationDtos.AircraftReservationDetail;
import ch.alpenflight.reservations.application.AircraftReservationDtos.AircraftReservationListItem;
import ch.alpenflight.reservations.application.AircraftReservationDtos.AircraftReservationPage;
import ch.alpenflight.reservations.application.AircraftReservationDtos.AircraftReservationPageRequest;
import ch.alpenflight.reservations.application.AircraftReservationDtos.AircraftReservationUpdateRequest;
import ch.alpenflight.reservations.application.AircraftReservationDtos.AircraftReservationValidateRequest;
import ch.alpenflight.reservations.application.AircraftReservationDtos.ReservationValidationResult;
import ch.alpenflight.reservations.application.AircraftReservationsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/aircraft-reservations", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Aircraft reservations",
        description = "Aircraft-reservation CRUD (tenant-scoped; conflict-409 on same-aircraft overlap).")
public class AircraftReservationsController {

    private final AircraftReservationsService service;
    private final UserPrincipalLookup userLookup;

    public AircraftReservationsController(AircraftReservationsService service,
                                          UserPrincipalLookup userLookup) {
        this.service = service;
        this.userLookup = userLookup;
    }

    @Operation(operationId = "getAircraftReservation",
            summary = "Read a single aircraft reservation by id.")
    @ApiResponse(responseCode = "200", description = "Reservation detail projection.")
    @ApiResponse(responseCode = "404", description = "No active reservation with that id in the tenant.")
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public AircraftReservationDetail getAircraftReservation(@PathVariable UUID id) {
        return service.getReservation(id);
    }

    @Operation(operationId = "pageAircraftReservations",
            summary = "Paged aircraft-reservation list (SPA-compat page shape). Body carries optional "
                    + "sorting (`start: asc|desc`) + a basic date-range search filter.")
    @ApiResponse(responseCode = "200", description = "One page of list rows + totalRows.")
    @PostMapping(path = "/page/{start}/{size}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @ReadOnlyQuery
    public AircraftReservationPage pageAircraftReservations(
            @PathVariable int start,
            @PathVariable int size,
            @RequestBody(required = false) @Nullable AircraftReservationPageRequest request) {
        return service.page(start, size, request);
    }

    @Operation(operationId = "listFutureAircraftReservations",
            summary = "Future reservations (start ≥ now), tenant-scoped, sorted by start — "
                    + "the scheduler/table default view.")
    @ApiResponse(responseCode = "200", description = "Future list rows, sorted by start asc.")
    @GetMapping("/future")
    @PreAuthorize("isAuthenticated()")
    public List<AircraftReservationListItem> listFutureAircraftReservations() {
        return service.listFuture();
    }

    @Operation(operationId = "listAircraftReservationsForDay",
            summary = "Reservations overlapping the given UTC day, tenant-scoped, sorted by start.")
    @ApiResponse(responseCode = "200", description = "List rows overlapping the day, sorted by start asc.")
    @GetMapping("/day/{date}")
    @PreAuthorize("isAuthenticated()")
    public List<AircraftReservationListItem> listAircraftReservationsForDay(
            @PathVariable @org.springframework.format.annotation.DateTimeFormat(iso =
                    org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.listForDay(date);
    }

    @Operation(operationId = "validateAircraftReservationOverlap",
            summary = "Pre-check a candidate aircraft slot for overlap (non-mutating). 200 with "
                    + "{valid,field,message} — surfaces the save-path conflict inline before save.")
    @ApiResponse(responseCode = "200", description = "Validation outcome (valid flag + offending field/message).")
    @PostMapping(path = "/validate", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @ReadOnlyQuery
    public ReservationValidationResult validateAircraftReservationOverlap(
            @Valid @RequestBody AircraftReservationValidateRequest req) {
        return service.validateOverlap(req);
    }

    @Operation(operationId = "createAircraftReservation",
            summary = "Create an aircraft reservation. Overlap on the same aircraft → 409; "
                    + "timed end ≤ start → 422.")
    @ApiResponse(responseCode = "201", description = "Created.")
    @ApiResponse(responseCode = "400", description = "Validation failed.")
    @ApiResponse(responseCode = "409", description = "Overlaps an existing booking on the aircraft.")
    @ApiResponse(responseCode = "422", description = "Timed reservation end is not after start.")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AircraftReservationDetail> createAircraftReservation(
            @Valid @RequestBody AircraftReservationCreateRequest req) {
        AircraftReservationDetail created = service.createReservation(req);
        return ResponseEntity
                .created(URI.create("/api/v1/aircraft-reservations/" + created.id()))
                .body(created);
    }

    @Operation(operationId = "updateAircraftReservation",
            summary = "Update an aircraft reservation. Self-excluded on the overlap probe "
                    + "(an in-place reschedule does not conflict with itself).")
    @ApiResponse(responseCode = "200", description = "Updated.")
    @ApiResponse(responseCode = "404", description = "No active reservation with that id in the tenant.")
    @ApiResponse(responseCode = "409", description = "Overlaps another booking on the aircraft.")
    @ApiResponse(responseCode = "422", description = "Timed reservation end is not after start.")
    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public AircraftReservationDetail updateAircraftReservation(
            @PathVariable UUID id,
            @Valid @RequestBody AircraftReservationUpdateRequest req) {
        return service.updateReservation(id, req);
    }

    @Operation(operationId = "deleteAircraftReservation",
            summary = "Soft-delete an aircraft reservation (frees the slot for a new overlapping booking).")
    @ApiResponse(responseCode = "204", description = "Deleted.")
    @ApiResponse(responseCode = "404", description = "No active reservation with that id in the tenant.")
    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> deleteAircraftReservation(
            @PathVariable UUID id,
            @AuthenticationPrincipal @Nullable Jwt jwt) {
        service.deleteReservation(id, principalUserId(jwt));
        return ResponseEntity.noContent().build();
    }

    private @Nullable UUID principalUserId(@Nullable Jwt jwt) {
        if (jwt == null) {
            return null;
        }
        return userLookup.resolveUserIdFor(jwt).orElse(null);
    }
}
