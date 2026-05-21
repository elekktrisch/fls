package ch.alpenflight.locations.web;

import ch.alpenflight.locations.application.LocationDtos.LocationCreateRequest;
import ch.alpenflight.locations.application.LocationDtos.LocationDetail;
import ch.alpenflight.locations.application.LocationDtos.LocationListItem;
import ch.alpenflight.locations.application.LocationDtos.LocationUpdateRequest;
import ch.alpenflight.locations.application.LocationsService;
import ch.alpenflight.platform.id.ClubId;
import ch.alpenflight.platform.id.LocationId;
import ch.alpenflight.platform.tenancy.Tenants;
import ch.alpenflight.platform.tenancy.UserTenantLookup;
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
 * SYSTEM_ADMINISTRATOR-only admin surface for cross-tenant Locations
 * management. Lets a sysadmin operate on a club's catalog without depending
 * on their JWT's {@code clubId} claim — the path-variable {@code clubId} is
 * the explicit target, and every method wraps {@link LocationsService}
 * calls in {@link Tenants#runAs(UUID, java.util.function.Supplier)} so
 * Hibernate's {@code @TenantId} discriminator sees the target club.
 *
 * <p>Mirrors {@link LocationsController}'s CRUD shape; the response bodies
 * are the same DTOs so the generated TS client reuses the existing models.
 *
 * <p>Operation on a club id that doesn't exist surfaces as a downstream
 * FK violation (the service-layer write hits {@code fk_location_club_id}).
 * No structural pre-check here — the FK is the structural gate.
 */
@RestController
@RequestMapping(path = "/api/v1/admin/locations/{clubId}", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Locations Admin", description = "SYSTEM_ADMINISTRATOR cross-tenant Locations management.")
@PreAuthorize("hasRole('SYSTEM_ADMINISTRATOR')")
public class LocationsAdminController {

    private final LocationsService service;
    private final UserTenantLookup userLookup;

    public LocationsAdminController(LocationsService service, UserTenantLookup userLookup) {
        this.service = service;
        this.userLookup = userLookup;
    }

    @Operation(summary = "List locations for {clubId}.")
    @ApiResponse(responseCode = "200", description = "Array of location listitem projections.")
    @GetMapping
    public List<LocationListItem> listLocations(@PathVariable ClubId clubId) {
        return Tenants.runAs(clubId.value(), service::listLocations);
    }

    @Operation(summary = "Read a single Location under {clubId}.")
    @ApiResponse(responseCode = "200", description = "Location detail projection.")
    @ApiResponse(responseCode = "404", description = "No active location with that id under {clubId}.")
    @GetMapping("/{id}")
    public LocationDetail getLocation(@PathVariable ClubId clubId, @PathVariable LocationId id) {
        return Tenants.runAs(clubId.value(), () -> service.getLocation(id));
    }

    @Operation(summary = "Create a new Location under {clubId}.")
    @ApiResponse(responseCode = "201", description = "Created; body is the new Location.")
    @ApiResponse(responseCode = "400", description = "Validation failed.")
    @ApiResponse(responseCode = "409", description = "ICAO code already in use within {clubId}.")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LocationDetail> createLocation(@PathVariable ClubId clubId,
                                                         @Valid @RequestBody LocationCreateRequest req) {
        LocationDetail created = Tenants.runAs(clubId.value(), () -> service.createLocation(req));
        URI location = URI.create("/api/v1/admin/locations/" + clubId + "/" + created.id());
        return ResponseEntity.created(location).body(created);
    }

    @Operation(summary = "Update a Location under {clubId}.")
    @ApiResponse(responseCode = "200", description = "Updated; body is the new state.")
    @ApiResponse(responseCode = "404", description = "No active location with that id under {clubId}.")
    @ApiResponse(responseCode = "409", description = "ICAO code already in use by another Location.")
    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public LocationDetail updateLocation(@PathVariable ClubId clubId,
                                         @PathVariable LocationId id,
                                         @Valid @RequestBody LocationUpdateRequest req) {
        return Tenants.runAs(clubId.value(), () -> service.updateLocation(id, req));
    }

    @Operation(summary = "Soft-delete a Location under {clubId}.")
    @ApiResponse(responseCode = "204", description = "Deleted.")
    @ApiResponse(responseCode = "404", description = "No active location with that id under {clubId}.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteLocation(@PathVariable ClubId clubId,
                                               @PathVariable LocationId id,
                                               @AuthenticationPrincipal @Nullable Jwt jwt) {
        UUID actorId = jwt == null ? null : userLookup.resolveUserIdFor(jwt).orElse(null);
        Tenants.runAs(clubId.value(), () -> service.softDeleteLocation(id, actorId));
        return ResponseEntity.noContent().build();
    }
}
