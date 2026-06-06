package ch.alpenflight.reservations.web;

import ch.alpenflight.platform.tenancy.UserPrincipalLookup;
import ch.alpenflight.reservations.application.AircraftReservationDtos.AircraftReservationCreateRequest;
import ch.alpenflight.reservations.application.AircraftReservationDtos.AircraftReservationDetail;
import ch.alpenflight.reservations.application.AircraftReservationDtos.AircraftReservationUpdateRequest;
import ch.alpenflight.reservations.application.AircraftReservationsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
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

/**
 * REST surface for the {@code AircraftReservation} aggregate (J-5 T-05) — the
 * CRUD seam. The new kebab-case resource {@code /api/v1/aircraft-reservations}
 * (the T-01 spec drives this, NOT the legacy {@code aircraftreservations} URL
 * shape or the {@code X-HTTP-Method-Override: PUT} verb tunnel — journey
 * assumption #2). The reservation-type dropdown is served from the sibling
 * {@code /api/v1/aircraft-reservation-types} listitems path.
 *
 * <p>The paged-list ({@code POST .../page/{start}/{size}}) and the
 * {@code /future} / {@code /day} overview endpoints are T-06, NOT here.
 *
 * <p><strong>Authz: legacy-open.</strong> Any authenticated tenant member may
 * CRUD reservations within their own tenant ({@code @TenantId} scopes every
 * read/write to the caller's club). Owner-or-admin edit/delete gating is a
 * deferred refinement (J-5 assumption #4), NOT shipped here.
 *
 * <p>Each endpoint carries an explicit {@code @Operation(operationId=...)} so
 * orval generates stable named client methods (not positional {@code getN}).
 */
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
