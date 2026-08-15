package ch.alpenflight.clubs.web;

import ch.alpenflight.clubs.application.ClubDtos.ClubCreateRequest;
import ch.alpenflight.clubs.application.ClubDtos.ClubResponse;
import ch.alpenflight.clubs.application.ClubDtos.ClubUpdateRequest;
import ch.alpenflight.clubs.application.ClubDtos.JoinCodeResponse;
import ch.alpenflight.clubs.application.ClubsService;
import ch.alpenflight.platform.id.ClubId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/clubs", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Clubs", description = "Clubs CRUD.")
public class ClubsController {

    private final ClubsService service;

    public ClubsController(ClubsService service) {
        this.service = service;
    }

    @Operation(summary = "List all clubs (active, sorted by name).")
    @ApiResponse(responseCode = "200", description = "Array of club projections.")
    @GetMapping
    @PreAuthorize("hasRole('SYSTEM_ADMINISTRATOR') or hasRole('FLIGHT_OPERATOR')")
    public List<ClubResponse> listClubs() {
        return service.listClubs();
    }

    @Operation(summary = "Read a single club by id.")
    @ApiResponse(responseCode = "200", description = "Club projection.")
    @ApiResponse(responseCode = "404", description = "No active club with that id.")
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMINISTRATOR') "
            + "or ((hasRole('CLUB_ADMINISTRATOR') or hasRole('FLIGHT_OPERATOR')) "
            + "and @tenant.isOwnClub(#id))")
    public ClubResponse getClub(@PathVariable ClubId id, Authentication authentication) {
        boolean joinCodeVisibleToCaller = hasRole(authentication, "ROLE_CLUB_ADMINISTRATOR");
        return service.getClub(id, joinCodeVisibleToCaller);
    }

    @Operation(summary = "Create a new club. Slug must be unique.")
    @ApiResponse(responseCode = "201", description = "Created; body is the new club.")
    @ApiResponse(responseCode = "409", description = "Slug already in use.")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('SYSTEM_ADMINISTRATOR')")
    public ResponseEntity<ClubResponse> createClub(@Valid @RequestBody ClubCreateRequest req) {
        ClubResponse created = service.createClub(req);
        return ResponseEntity.created(URI.create("/api/v1/clubs/" + created.id())).body(created);
    }

    @Operation(summary = "Update an existing club. `clubKey` is immutable post-create.")
    @ApiResponse(responseCode = "200", description = "Updated; body is the new state.")
    @ApiResponse(responseCode = "404", description = "No active club with that id.")
    @ApiResponse(responseCode = "409", description = "Slug already in use by another club.")
    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('SYSTEM_ADMINISTRATOR') "
            + "or (hasRole('CLUB_ADMINISTRATOR') and @tenant.isOwnClub(#id))")
    public ClubResponse updateClub(@PathVariable ClubId id, @Valid @RequestBody ClubUpdateRequest req) {
        return service.updateClub(id, req);
    }

    @Operation(summary = "Soft-delete a club.")
    @ApiResponse(responseCode = "204", description = "Deleted.")
    @ApiResponse(responseCode = "404", description = "No active club with that id.")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMINISTRATOR')")
    public ResponseEntity<Void> deleteClub(@PathVariable ClubId id) {
        service.deleteClub(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Rotate the club's join code to a fresh value.")
    @ApiResponse(responseCode = "200", description = "Rotated; body carries the new code.")
    @ApiResponse(responseCode = "404", description = "No active club with that id.")
    @PostMapping(path = "/{clubId}/join-code/rotate")
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR') and @tenant.isOwnClub(#clubId)")
    public JoinCodeResponse rotateJoinCode(@PathVariable ClubId clubId) {
        return service.rotateJoinCode(clubId);
    }

    private static boolean hasRole(Authentication authentication, String role) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(role::equals);
    }
}
