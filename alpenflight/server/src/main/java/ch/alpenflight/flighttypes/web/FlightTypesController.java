package ch.alpenflight.flighttypes.web;

import ch.alpenflight.flighttypes.application.FlightTypeDtos.FlightTypeCreateRequest;
import ch.alpenflight.flighttypes.application.FlightTypeDtos.FlightTypeDetail;
import ch.alpenflight.flighttypes.application.FlightTypeDtos.FlightTypeListItem;
import ch.alpenflight.flighttypes.application.FlightTypeDtos.FlightTypeUpdateRequest;
import ch.alpenflight.flighttypes.application.FlightTypesService;
import ch.alpenflight.platform.id.FlightTypeId;
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

@RestController
@RequestMapping(path = "/api/v1/flight-types", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "FlightTypes", description = "FlightType CRUD (per-club tenant-scoped masterdata).")
public class FlightTypesController {

    private final FlightTypesService service;
    private final UserPrincipalLookup userLookup;

    public FlightTypesController(FlightTypesService service, UserPrincipalLookup userLookup) {
        this.service = service;
        this.userLookup = userLookup;
    }

    @Operation(summary = "List all active FlightTypes for the caller's tenant, sorted by name.")
    @ApiResponse(responseCode = "200", description = "Array of FlightType listitem projections.")
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<FlightTypeListItem> listFlightTypes() {
        return service.listFlightTypes();
    }

    @Operation(summary = "Read a single FlightType by id.")
    @ApiResponse(responseCode = "200", description = "FlightType detail projection.")
    @ApiResponse(responseCode = "404",
            description = "No active FlightType with that id (includes cross-tenant lookup).")
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public FlightTypeDetail getFlightType(@PathVariable FlightTypeId id) {
        return service.getFlightType(id);
    }

    @Operation(summary = "Register a new FlightType in the caller's tenant.")
    @ApiResponse(responseCode = "201", description = "Created.")
    @ApiResponse(responseCode = "400", description = "Validation failed.")
    @ApiResponse(responseCode = "409", description = "Name already in use for this tenant.")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public ResponseEntity<FlightTypeDetail> registerFlightType(
            @Valid @RequestBody FlightTypeCreateRequest req) {
        FlightTypeDetail created = service.registerFlightType(req);
        return ResponseEntity.created(URI.create("/api/v1/flight-types/" + created.id()))
                .body(created);
    }

    @Operation(summary = "Update a FlightType.")
    @ApiResponse(responseCode = "200", description = "Updated.")
    @ApiResponse(responseCode = "404", description = "No active FlightType with that id.")
    @ApiResponse(responseCode = "409", description = "Name already in use for this tenant.")
    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public FlightTypeDetail updateFlightType(@PathVariable FlightTypeId id,
                                             @Valid @RequestBody FlightTypeUpdateRequest req) {
        return service.updateFlightType(id, req);
    }

    @Operation(summary = "Soft-delete a FlightType.")
    @ApiResponse(responseCode = "204", description = "Deleted.")
    @ApiResponse(responseCode = "404", description = "No active FlightType with that id.")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public ResponseEntity<Void> deleteFlightType(@PathVariable FlightTypeId id,
                                                 @AuthenticationPrincipal @Nullable Jwt jwt) {
        service.softDeleteFlightType(id, principalUserId(jwt));
        return ResponseEntity.noContent().build();
    }

    private @Nullable UUID principalUserId(@Nullable Jwt jwt) {
        if (jwt == null) {
            return null;
        }
        return userLookup.resolveUserIdFor(jwt).orElse(null);
    }
}
