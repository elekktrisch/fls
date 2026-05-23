package ch.alpenflight.aircraft.web;

import ch.alpenflight.aircraft.application.AircraftDtos.AircraftCounterHistory;
import ch.alpenflight.aircraft.application.AircraftDtos.AircraftCounterRecordRequest;
import ch.alpenflight.aircraft.application.AircraftDtos.AircraftCreateRequest;
import ch.alpenflight.aircraft.application.AircraftDtos.AircraftDetail;
import ch.alpenflight.aircraft.application.AircraftDtos.AircraftListItem;
import ch.alpenflight.aircraft.application.AircraftDtos.AircraftOperatingCounterResponse;
import ch.alpenflight.aircraft.application.AircraftDtos.AircraftPickerItem;
import ch.alpenflight.aircraft.application.AircraftDtos.AircraftStateChangeRequest;
import ch.alpenflight.aircraft.application.AircraftDtos.AircraftStateHistory;
import ch.alpenflight.aircraft.application.AircraftDtos.AircraftStateHistoryEntryResponse;
import ch.alpenflight.aircraft.application.AircraftDtos.AircraftTransferOwnershipRequest;
import ch.alpenflight.aircraft.application.AircraftDtos.AircraftUpdateRequest;
import ch.alpenflight.aircraft.application.AircraftTypeSlice;
import ch.alpenflight.aircraft.application.AircraftsService;
import ch.alpenflight.platform.id.AircraftId;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for the Aircraft aggregate. Per ADR 0005 the path is plural
 * {@code /api/v1/aircraft} (mass noun — no plural form).
 *
 * <p>Aircraft is <strong>tenant-scoped</strong> via Hibernate's
 * {@code @TenantId} discriminator on {@code Aircraft.managingClubId}
 * (S-159). Reads and writes are filtered structurally to the caller's
 * managing tenant; a CLUB_ADMIN of A asking for B's aircraft receives a
 * {@code 404 Not Found} — the row is invisible under A's tenant scope, not
 * a {@code 403}. This is the IDOR gate, and it is structural.
 *
 * <p>Role gates: CLUB_ADMINISTRATOR for register / update / soft-delete /
 * transfer-ownership; CLUB_ADMINISTRATOR or FLIGHT_OPERATOR for state
 * changes and counter recording (operational events).
 *
 * <p>{@code owner_club_id} / {@code aircraft_owner_person_id} are NOT
 * settable via {@link AircraftCreateRequest} or {@link AircraftUpdateRequest}
 * (A04 mass-assignment defense). Newly-registered aircraft default to
 * own-club ownership; later changes go through
 * {@code POST /transfer-ownership} (CLUB_ADMINISTRATOR; ownership only,
 * managing tenant unchanged).
 */
@RestController
@RequestMapping(path = "/api/v1/aircraft", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Aircraft", description = "Aircraft CRUD (per-club tenant-scoped masterdata).")
public class AircraftsController {

    private final AircraftsService service;
    private final UserPrincipalLookup userLookup;

    public AircraftsController(AircraftsService service, UserPrincipalLookup userLookup) {
        this.service = service;
        this.userLookup = userLookup;
    }

    @Operation(summary = "List all active aircraft, sorted by immatriculation. "
            + "Optional `type` query param slices the list per legacy semantics: "
            + "GLIDER (pure + with-motor), MOTOR (excludes glider-with-motor), TOWING (any type).")
    @ApiResponse(responseCode = "200", description = "Array of aircraft listitem projections.")
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<AircraftListItem> listAircraft(
            @RequestParam(name = "type", required = false) @Nullable AircraftTypeSlice type) {
        return service.listAircraft(type);
    }

    @Operation(summary = "Slim picker list (id + immatriculation + type + isTowingAircraft).")
    @ApiResponse(responseCode = "200", description = "Slim picker projections for FE pickers.")
    @GetMapping("/picker")
    @PreAuthorize("isAuthenticated()")
    public List<AircraftPickerItem> listAircraftForPicker() {
        return service.listAircraftForPicker();
    }

    @Operation(summary = "Read a single Aircraft by id (current state + latest counter inlined).")
    @ApiResponse(responseCode = "200", description = "Aircraft detail projection.")
    @ApiResponse(responseCode = "404", description = "No active aircraft with that id.")
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public AircraftDetail getAircraft(@PathVariable AircraftId id) {
        return service.getAircraft(id);
    }

    @Operation(summary = "Register a new Aircraft. Immatriculation must be globally unique.")
    @ApiResponse(responseCode = "201", description = "Created.")
    @ApiResponse(responseCode = "400", description = "Validation failed.")
    @ApiResponse(responseCode = "409", description = "Immatriculation already in use.")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public ResponseEntity<AircraftDetail> registerAircraft(
            @Valid @RequestBody AircraftCreateRequest req) {
        AircraftDetail created = service.registerAircraft(req);
        return ResponseEntity.created(URI.create("/api/v1/aircraft/" + created.id())).body(created);
    }

    @Operation(summary = "Update an Aircraft (owner fields excluded — see transfer-ownership).")
    @ApiResponse(responseCode = "200", description = "Updated.")
    @ApiResponse(responseCode = "404", description = "No active aircraft with that id.")
    @ApiResponse(responseCode = "409", description = "Immatriculation already in use.")
    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public AircraftDetail updateAircraft(@PathVariable AircraftId id,
                                         @Valid @RequestBody AircraftUpdateRequest req) {
        return service.updateAircraft(id, req);
    }

    @Operation(summary = "Soft-delete an Aircraft.")
    @ApiResponse(responseCode = "204", description = "Deleted.")
    @ApiResponse(responseCode = "404", description = "No active aircraft with that id.")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public ResponseEntity<Void> deleteAircraft(@PathVariable AircraftId id,
                                               @AuthenticationPrincipal @Nullable Jwt jwt) {
        service.softDeleteAircraft(id, principalUserId(jwt));
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Transfer ownership of an Aircraft (CLUB_ADMINISTRATOR of the managing club).")
    @ApiResponse(responseCode = "200", description = "Ownership transferred.")
    @ApiResponse(responseCode = "404", description = "No active aircraft with that id.")
    @PostMapping(path = "/{id}/transfer-ownership", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public AircraftDetail transferOwnership(@PathVariable AircraftId id,
                                            @Valid @RequestBody AircraftTransferOwnershipRequest req) {
        return service.transferOwnership(id, req);
    }

    @Operation(summary = "Record a new airworthiness state (closes the previous open period).")
    @ApiResponse(responseCode = "201", description = "State change recorded.")
    @ApiResponse(responseCode = "409", description = "Concurrent modification or invalid validFrom.")
    @PostMapping(path = "/{id}/states", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('CLUB_ADMINISTRATOR', 'FLIGHT_OPERATOR')")
    public ResponseEntity<AircraftStateHistoryEntryResponse> changeState(
            @PathVariable AircraftId id,
            @Valid @RequestBody AircraftStateChangeRequest req) {
        AircraftStateHistoryEntryResponse body = service.changeAircraftState(id, req);
        return ResponseEntity.created(URI.create(
                "/api/v1/aircraft/" + id + "/states/" + body.id())).body(body);
    }

    @Operation(summary = "List state history for an Aircraft (newest first).")
    @ApiResponse(responseCode = "200", description = "State history.")
    @GetMapping("/{id}/states")
    @PreAuthorize("isAuthenticated()")
    public AircraftStateHistory listStateHistory(@PathVariable AircraftId id) {
        return service.getStateHistory(id);
    }

    @Operation(summary = "Record a new operating-counter snapshot.")
    @ApiResponse(responseCode = "201", description = "Counter recorded.")
    @ApiResponse(responseCode = "409", description = "Non-monotonic totals or duplicate at_date_time.")
    @PostMapping(path = "/{id}/counters", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('CLUB_ADMINISTRATOR', 'FLIGHT_OPERATOR')")
    public ResponseEntity<AircraftOperatingCounterResponse> recordCounter(
            @PathVariable AircraftId id,
            @Valid @RequestBody AircraftCounterRecordRequest req) {
        AircraftOperatingCounterResponse body = service.recordAircraftCounter(id, req);
        return ResponseEntity.created(URI.create(
                "/api/v1/aircraft/" + id + "/counters/" + body.id())).body(body);
    }

    @Operation(summary = "List counter history for an Aircraft (newest first).")
    @ApiResponse(responseCode = "200", description = "Counter history.")
    @GetMapping("/{id}/counters")
    @PreAuthorize("isAuthenticated()")
    public AircraftCounterHistory listCounterHistory(@PathVariable AircraftId id) {
        return service.getCounterHistory(id);
    }

    private @Nullable UUID principalUserId(@Nullable Jwt jwt) {
        if (jwt == null) {
            return null;
        }
        return userLookup.resolveUserIdFor(jwt).orElse(null);
    }
}
