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
        MeView view = meService.resolve(jwt);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(MeResponse.from(view));
    }
}
