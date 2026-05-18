package ch.alpenflight.clubs;

import ch.alpenflight.clubs.ClubDtos.ClubCreateRequest;
import ch.alpenflight.clubs.ClubDtos.ClubResponse;
import ch.alpenflight.clubs.ClubDtos.ClubUpdateRequest;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for the Clubs aggregate. Per ADR 0005 the path is plural
 * {@code /api/v1/clubs}.
 *
 * <p>The {@code @PreAuthorize} predicates are the load-bearing security gate
 * — under the {@code mock-auth} profile (S-048) the principal is hard-coded
 * SYSTEM_ADMINISTRATOR, so the disjunctive {@code CLUB_ADMINISTRATOR} clause
 * is never reached today. The predicate shape stays when the real OAuth2
 * resource server is in play; only the principal source flips.
 *
 * <p>{@code @PathVariable ClubId id} resolves through
 * {@code ClubIdPathConverter} so callers send the prefixed external form
 * {@code clb_<26-char>}. The SpEL predicates dereference {@code #id.value()}
 * to compare against the JWT's raw-UUID {@code clubId} claim.
 *
 * <p>Walking-skeleton scope: the DTO omits country / club-state pickers —
 * the service hard-codes the canonical CH / ACTIVE seed UUIDs. S-047
 * introduces FK pickers and the DTO grows then.
 */
@RestController
@RequestMapping(path = "/api/v1/clubs", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Clubs", description = "Clubs CRUD (S-048 walking skeleton, mocked authorization).")
public class ClubsController {

    private final ClubsService service;

    public ClubsController(ClubsService service) {
        this.service = service;
    }

    @Operation(summary = "List all clubs (active, sorted by name).")
    @ApiResponse(responseCode = "200", description = "Array of club projections.")
    @GetMapping
    @PreAuthorize("hasRole('SYSTEM_ADMINISTRATOR')")
    public List<ClubResponse> listClubs() {
        return service.listClubs();
    }

    @Operation(summary = "Read a single club by id.")
    @ApiResponse(responseCode = "200", description = "Club projection.")
    @ApiResponse(responseCode = "404", description = "No active club with that id.")
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMINISTRATOR') "
            + "or (hasRole('CLUB_ADMINISTRATOR') and #id.value().toString() == principal.claims['clubId'])")
    public ClubResponse getClub(@PathVariable ClubId id) {
        return service.getClub(id);
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
            + "or (hasRole('CLUB_ADMINISTRATOR') and #id.value().toString() == principal.claims['clubId'])")
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
}
