package ch.alpenflight.me.web;

import ch.alpenflight.me.application.MeService;
import ch.alpenflight.me.application.MeView;
import ch.alpenflight.users.application.SelfProfileUpdate;
import ch.alpenflight.users.application.UsersService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code PATCH /api/v1/me/profile} — the caller-scoped Account self-edit
 * (J-4 T-04). A logged-in principal edits their OWN User aggregate's mutable
 * self-fields (friendlyName, notificationEmail, phoneNumber, languageId).
 *
 * <p><strong>No {@code :id} path param.</strong> The User to edit is resolved
 * from the JWT {@code sub} (via {@link UsersService#updateOwnProfile}, which
 * looks up by {@code keycloak_sub}), never from the request — so a caller can
 * only ever mutate their own row. Identity-binding fields (username, clubId,
 * keycloakSub) and admin-only fields (remarks, roles, personId, account-state)
 * are not on the request DTO and are preserved unchanged.
 *
 * <p>Authz: any authenticated principal — the surface is principal-scoped by
 * construction, like {@code GET /api/v1/me}.
 */
@RestController
@RequestMapping(path = "/api/v1/me", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "me", description = "Authenticated-principal view")
class MeProfileController {

    private final UsersService usersService;
    private final MeService meService;

    MeProfileController(UsersService usersService, MeService meService) {
        this.usersService = usersService;
        this.meService = meService;
    }

    @PatchMapping(path = "/profile", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(
            operationId = "updateMyProfile",
            summary = "Edit the caller's own Account self-fields (friendlyName, notificationEmail, "
                    + "phoneNumber, languageId). Caller resolved from the JWT — no :id. Username / "
                    + "clubId / keycloakSub are immutable; remarks (admin-only) is preserved. Returns "
                    + "the updated /me projection.")
    @ApiResponse(responseCode = "200", description = "Updated; body is the refreshed /me projection.")
    @ApiResponse(responseCode = "400", description = "Validation failed (blank name, bad email, unknown language).")
    ResponseEntity<MeResponse> updateProfile(@Valid @RequestBody MeProfileUpdateRequest req,
                                             @AuthenticationPrincipal Jwt jwt) {
        usersService.updateOwnProfile(jwt, new SelfProfileUpdate(
                req.friendlyName(), req.notificationEmail(), req.phoneNumber(), req.languageId()));
        // Re-resolve through the same projection /me reads from, so the SPA
        // reflects persisted state on the PATCH response (no re-GET needed).
        MeView view = meService.resolve(jwt);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(MeResponse.from(view));
    }
}
