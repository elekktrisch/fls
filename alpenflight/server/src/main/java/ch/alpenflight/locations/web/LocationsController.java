package ch.alpenflight.locations.web;

import ch.alpenflight.locations.application.LocationDtos.LocationCreateRequest;
import ch.alpenflight.locations.application.LocationDtos.LocationDetail;
import ch.alpenflight.locations.application.LocationDtos.LocationListItem;
import ch.alpenflight.locations.application.LocationDtos.LocationUpdateRequest;
import ch.alpenflight.locations.application.LocationsService;
import ch.alpenflight.platform.id.LocationId;
import ch.alpenflight.platform.tenancy.UserPrincipalLookup;
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
 * REST surface for the Locations aggregate. Per ADR 0005 the path is plural
 * {@code /api/v1/locations}. Since S-049b Locations are TENANT_SCOPED
 * masterdata: reads return only the caller's own club's Locations
 * (Hibernate's {@code @TenantId} discriminator filter); writes are open to
 * CLUB_ADMINISTRATOR of the caller's tenant. SYSTEM_ADMINISTRATOR has no
 * tenant context and therefore no rights on this surface (S-159 strip).
 *
 * <p>{@code @PathVariable LocationId id} resolves through
 * {@code LocationIdPathConverter} so callers send the prefixed external form
 * {@code loc-<uuid>}. A CLUB_ADMIN of A targeting an id owned by club B
 * receives a {@code 404 Not Found} — the row is invisible under A's tenant
 * scope, not a {@code 403}. This is the IDOR gate, and it is structural.
 */
@RestController
@RequestMapping(path = "/api/v1/locations", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Locations", description = "Locations CRUD (per-club masterdata).")
public class LocationsController {

    private final LocationsService service;
    private final UserPrincipalLookup userLookup;

    public LocationsController(LocationsService service, UserPrincipalLookup userLookup) {
        this.service = service;
        this.userLookup = userLookup;
    }

    @Operation(summary = "List all locations (active, sorted by sort_indicator + name).")
    @ApiResponse(responseCode = "200", description = "Array of location listitem projections.")
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<LocationListItem> listLocations() {
        return service.listLocations();
    }

    @Operation(summary = "Read a single Location by id (includes nested IOPs).")
    @ApiResponse(responseCode = "200", description = "Location detail projection.")
    @ApiResponse(responseCode = "404", description = "No active location with that id.")
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public LocationDetail getLocation(@PathVariable LocationId id) {
        return service.getLocation(id);
    }

    @Operation(summary = "Create a new Location. ICAO code must be unique among active rows.")
    @ApiResponse(responseCode = "201", description = "Created; body is the new Location.")
    @ApiResponse(responseCode = "400", description = "Validation failed (ICAO format or unknown reference).")
    @ApiResponse(responseCode = "409", description = "ICAO code already in use.")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public ResponseEntity<LocationDetail> createLocation(
            @Valid @RequestBody LocationCreateRequest req) {
        LocationDetail created = service.createLocation(req);
        return ResponseEntity.created(URI.create("/api/v1/locations/" + created.id())).body(created);
    }

    @Operation(summary = "Update a Location. PUT body replaces the full IOP list.")
    @ApiResponse(responseCode = "200", description = "Updated; body is the new state.")
    @ApiResponse(responseCode = "400", description = "Validation failed.")
    @ApiResponse(responseCode = "404", description = "No active location with that id.")
    @ApiResponse(responseCode = "409", description = "ICAO code already in use by another Location.")
    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public LocationDetail updateLocation(@PathVariable LocationId id,
                                         @Valid @RequestBody LocationUpdateRequest req) {
        return service.updateLocation(id, req);
    }

    @Operation(summary = "Soft-delete a Location.")
    @ApiResponse(responseCode = "204", description = "Deleted.")
    @ApiResponse(responseCode = "404", description = "No active location with that id.")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public ResponseEntity<Void> deleteLocation(@PathVariable LocationId id,
                                               @AuthenticationPrincipal @Nullable Jwt jwt) {
        service.softDeleteLocation(id, principalUserId(jwt));
        return ResponseEntity.noContent().build();
    }

    /**
     * Resolves the internal {@code user.id} for the audit trail.
     * {@code jwt.getSubject()} (OIDC {@code sub}) maps to {@code user.id} via
     * {@link UserPrincipalLookup#resolveUserIdFor(Jwt)}; federated subs that
     * don't match the {@code keycloak_sub} mapping yield null until those
     * IdPs onboard.
     */
    private @Nullable UUID principalUserId(@Nullable Jwt jwt) {
        if (jwt == null) {
            return null;
        }
        return userLookup.resolveUserIdFor(jwt).orElse(null);
    }
}
