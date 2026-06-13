package ch.alpenflight.users.web;

import ch.alpenflight.platform.id.UserId;
import ch.alpenflight.platform.tenancy.UserPrincipalLookup;
import ch.alpenflight.users.application.UserDtos.UserInviteRequest;
import ch.alpenflight.users.application.UserDtos.UserListItem;
import ch.alpenflight.users.application.UserDtos.UserResponse;
import ch.alpenflight.users.application.UserDtos.UserUpdateRequest;
import ch.alpenflight.users.application.UsersService;
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
 * REST surface for the User aggregate (per-club CLUB_ADMIN view).
 *
 * <p>The {@code notification_email} field is intentionally distinct from
 * Keycloak's login {@code email} — keeping them decoupled avoids
 * re-creating the entanglement ADR 0007 aimed to escape. The two fields
 * happen to share an initial value on invite, but the SPA edit surface
 * never re-syncs them.
 *
 * <p><strong>Tenant scoping</strong>: explicit {@code club_id} predicate in
 * every repo query plus {@code @userAccess.canView/Edit} SpEL guards. 404
 * not 403 for cross-tenant reads (IDOR contract).
 */
@RestController
@RequestMapping(path = "/api/v1/users", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Users", description = "User CRUD + role assignment (CLUB_ADMINISTRATOR cabin).")
public class UsersController {

    private final UsersService service;
    private final UserPrincipalLookup userLookup;

    public UsersController(UsersService service, UserPrincipalLookup userLookup) {
        this.service = service;
        this.userLookup = userLookup;
    }

    @Operation(summary = "List active users in the caller's club. Live KC fields included (roles, enabled, invitePending).")
    @ApiResponse(responseCode = "200", description = "Array of user listitem projections.")
    @GetMapping
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public List<UserListItem> listUsers() {
        return service.listInCurrentTenant();
    }

    @Operation(summary = "Read one user. 404 (not 403) cross-tenant — IDOR contract.")
    @ApiResponse(responseCode = "200", description = "User detail.")
    @ApiResponse(responseCode = "404", description = "Not found or not visible to caller's club.")
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public UserResponse getUser(@PathVariable UserId id) {
        // No @userAccess.canView SpEL gate: it would short-circuit
        // cross-tenant reads at 403, breaking the documented S-051 404-not-403
        // IDOR contract. Service-layer load enforces the tenant scope and
        // throws UserNotFoundException → 404.
        return service.getUser(id);
    }

    @Operation(summary = "Invite a new user. Creates the Keycloak identity and the local row in one transaction; UPDATE_PASSWORD email fires after commit.")
    @ApiResponse(responseCode = "201", description = "Created.")
    @ApiResponse(responseCode = "400", description = "Validation failed.")
    @ApiResponse(responseCode = "403", description = "Role grant rejected (e.g. SYSTEM_ADMINISTRATOR).")
    @ApiResponse(responseCode = "409", description = "Username already taken.")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public ResponseEntity<UserResponse> inviteUser(@Valid @RequestBody UserInviteRequest req,
                                                   @AuthenticationPrincipal @Nullable Jwt jwt) {
        UserResponse created = service.invite(req, jwt);
        return ResponseEntity.created(URI.create("/api/v1/users/" + created.id())).body(created);
    }

    @Operation(summary = "Update profile + role assignments. Username is identity-binding (not editable here).")
    @ApiResponse(responseCode = "200", description = "Updated.")
    @ApiResponse(responseCode = "403", description = "Role grant rejected.")
    @ApiResponse(responseCode = "404", description = "Not found or not visible to caller's club.")
    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR') and @userAccess.canEdit(#id, #jwt)")
    public UserResponse updateUser(@PathVariable UserId id,
                                   @Valid @RequestBody UserUpdateRequest req,
                                   @AuthenticationPrincipal @Nullable Jwt jwt) {
        return service.update(id, req, jwt);
    }

    @Operation(summary = "Soft-delete user. Refused (409) for self-delete and for the last CLUB_ADMINISTRATOR of a club.")
    @ApiResponse(responseCode = "204", description = "Deleted.")
    @ApiResponse(responseCode = "404", description = "Not found.")
    @ApiResponse(responseCode = "409", description = "Self-delete or last-admin refusal.")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR') and @userAccess.canEdit(#id, #jwt)")
    public ResponseEntity<Void> deleteUser(@PathVariable UserId id,
                                           @AuthenticationPrincipal @Nullable Jwt jwt) {
        service.softDelete(id, principalUserId(jwt));
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Re-send the Keycloak UPDATE_PASSWORD action email. Idempotent.")
    @ApiResponse(responseCode = "204", description = "Sent.")
    @ApiResponse(responseCode = "404", description = "Not found.")
    @ApiResponse(responseCode = "409", description = "User has no Keycloak identity attached.")
    @PostMapping("/{id}/resend-invite")
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR') and @userAccess.canEdit(#id, #jwt)")
    public ResponseEntity<Void> resendInvite(@PathVariable UserId id,
                                             @AuthenticationPrincipal @Nullable Jwt jwt) {
        service.resendInvite(id);
        return ResponseEntity.noContent().build();
    }

    private @Nullable UUID principalUserId(@Nullable Jwt jwt) {
        if (jwt == null) {
            return null;
        }
        return userLookup.resolveUserIdFor(jwt).orElse(null);
    }
}
