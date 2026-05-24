package ch.alpenflight.persons.web;

import ch.alpenflight.persons.application.PersonDtos.PersonClubRequest;
import ch.alpenflight.persons.application.PersonDtos.PersonCreateRequest;
import ch.alpenflight.persons.application.PersonDtos.PersonListItem;
import ch.alpenflight.persons.application.PersonDtos.PersonLookupRequest;
import ch.alpenflight.persons.application.PersonDtos.PersonLookupResult;
import ch.alpenflight.persons.application.PersonDtos.PersonResponse;
import ch.alpenflight.persons.application.PersonDtos.PersonUpdateRequest;
import ch.alpenflight.persons.application.PersonsService;
import ch.alpenflight.platform.id.PersonId;
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
 * REST surface for the Person aggregate (per-club view).
 *
 * <p><strong>Tenant scoping</strong>: the list query JOINs through
 * {@code person_club} so Hibernate's {@code @TenantId} filters the result
 * to the caller's tenant. Detail / update / delete reads load by PK
 * (cross-tenant) but the service refuses with 404 if the caller's tenant
 * has no alive PersonClub for the Person — 404, not 403, so the row's
 * existence stays opaque to other tenants (IDOR contract).
 *
 * <p><strong>Authorization</strong>: per S-159, SYSTEM_ADMINISTRATOR is
 * stripped from tenant-scoped HTTP endpoints. {@code CLUB_ADMINISTRATOR}
 * is the role for every endpoint here; cross-cutting sysadmin Person
 * operations would live under {@code /api/v1/admin/persons/**} (deferred
 * until cutover demands it).
 *
 * <p>Audit emission is in the service; controller methods do not call
 * {@code AuditTrail} directly.
 */
@RestController
@RequestMapping(path = "/api/v1/persons", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Persons", description = "Person CRUD (cross-tenant root with tenant-scoped PersonClub child).")
public class PersonsController {

    private final PersonsService service;
    private final UserPrincipalLookup userLookup;

    public PersonsController(PersonsService service, UserPrincipalLookup userLookup) {
        this.service = service;
        this.userLookup = userLookup;
    }

    @Operation(summary = "List Persons with an alive membership in the caller's tenant. Sorted by lastname, firstname.")
    @ApiResponse(responseCode = "200", description = "Array of Person listitem projections.")
    @GetMapping
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public List<PersonListItem> listPersons() {
        return service.listInCurrentTenant();
    }

    @Operation(summary = "Exact-match lookup for the add-existing flow. Provide email OR full identity triple (firstname + lastname + birthday). Partial triples are rejected.")
    @ApiResponse(responseCode = "200", description = "Matches (≤ 5), with `alreadyInThisClub` per row.")
    @ApiResponse(responseCode = "400", description = "Partial triple, or no lookup key.")
    @PostMapping(path = "/lookup", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public PersonLookupResult lookupPerson(@Valid @RequestBody PersonLookupRequest req) {
        return service.lookup(req);
    }

    @Operation(summary = "Read a Person by id. Returns 404 if no alive PersonClub for this Person is visible to the caller's tenant.")
    @ApiResponse(responseCode = "200", description = "Person detail projection.")
    @ApiResponse(responseCode = "404", description = "No Person, or not visible to the caller's tenant.")
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public PersonResponse getPerson(@PathVariable PersonId id) {
        return service.getPerson(id);
    }

    @Operation(summary = "Create a new Person, optionally with an inline initial PersonClub for the caller's tenant.")
    @ApiResponse(responseCode = "201", description = "Created.")
    @ApiResponse(responseCode = "400", description = "Validation failed.")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public ResponseEntity<PersonResponse> createPerson(@Valid @RequestBody PersonCreateRequest req) {
        PersonResponse created = service.createPerson(req);
        return ResponseEntity.created(URI.create("/api/v1/persons/" + created.id())).body(created);
    }

    @Operation(summary = "Update Person identity / contact / licences. PersonClub mutations go through /clubs/current.")
    @ApiResponse(responseCode = "200", description = "Updated.")
    @ApiResponse(responseCode = "404", description = "No Person, or not visible to the caller's tenant.")
    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public PersonResponse updatePerson(@PathVariable PersonId id,
                                       @Valid @RequestBody PersonUpdateRequest req) {
        return service.updatePerson(id, req);
    }

    @Operation(summary = "Soft-delete a Person. Refused (409) if the Person has active memberships in another tenant.")
    @ApiResponse(responseCode = "204", description = "Deleted.")
    @ApiResponse(responseCode = "404", description = "No Person, or not visible to the caller's tenant.")
    @ApiResponse(responseCode = "409", description = "Person has memberships in another tenant.")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public ResponseEntity<Void> deletePerson(@PathVariable PersonId id,
                                             @AuthenticationPrincipal @Nullable Jwt jwt) {
        service.softDeletePerson(id, principalUserId(jwt));
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Attach an existing Person to the caller's tenant. Creates a new PersonClub row.")
    @ApiResponse(responseCode = "200", description = "Person now has an alive PersonClub in the caller's tenant.")
    @ApiResponse(responseCode = "404", description = "Person not found (or never existed).")
    @ApiResponse(responseCode = "409", description = "Person already has an active membership in this club.")
    @PostMapping(path = "/{id}/clubs", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public PersonResponse attachExistingPerson(@PathVariable PersonId id,
                                               @Valid @RequestBody PersonClubRequest req) {
        return service.attachExistingPerson(id, req);
    }

    @Operation(summary = "Update the caller-tenant's PersonClub fields (memberNumber, memberStateId, role flags, notification prefs, isActive).")
    @ApiResponse(responseCode = "200", description = "Updated.")
    @ApiResponse(responseCode = "404", description = "No alive PersonClub for this Person in the caller's tenant.")
    @PutMapping(path = "/{id}/clubs/current", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public PersonResponse updateCurrentClubMembership(@PathVariable PersonId id,
                                                      @Valid @RequestBody PersonClubRequest req) {
        return service.updateCurrentClubMembership(id, req);
    }

    @Operation(summary = "Leave the caller's club (soft-delete the caller-tenant PersonClub).")
    @ApiResponse(responseCode = "204", description = "Left.")
    @ApiResponse(responseCode = "404", description = "No alive PersonClub for this Person in the caller's tenant.")
    @DeleteMapping("/{id}/clubs/current")
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public ResponseEntity<Void> leaveCurrentClub(@PathVariable PersonId id,
                                                 @AuthenticationPrincipal @Nullable Jwt jwt) {
        service.leaveCurrentClub(id, principalUserId(jwt));
        return ResponseEntity.noContent().build();
    }

    private @Nullable UUID principalUserId(@Nullable Jwt jwt) {
        if (jwt == null) {
            return null;
        }
        return userLookup.resolveUserIdFor(jwt).orElse(null);
    }
}
