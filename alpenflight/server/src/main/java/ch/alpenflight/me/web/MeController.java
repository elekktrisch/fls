package ch.alpenflight.me.web;

import ch.alpenflight.me.application.MeService;
import ch.alpenflight.me.application.MeView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/me} — authenticated-principal projection. Foundational
 * read surface consumed by the SPA (S-165 home page) and future
 * profile / settings / view-as / per-person-stats stories.
 *
 * <p>Authz: any authenticated principal. No tenant gate, no role gate —
 * the response is principal-scoped by construction (a caller can only
 * read their own {@code /me}).
 */
@RestController
@RequestMapping(path = "/api/v1/me", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "me", description = "Authenticated-principal view")
class MeController {

    private final MeService meService;

    MeController(MeService meService) {
        this.meService = meService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Authenticated-principal projection (id, personId, clubId, roles, "
            + "firstName, lastName, email, username). personId is null for sysadmins / "
            + "unmapped federated users.")
    MeResponse get(@AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        MeView view = meService.resolve(jwt, authentication.getAuthorities());
        return MeResponse.from(view);
    }
}
