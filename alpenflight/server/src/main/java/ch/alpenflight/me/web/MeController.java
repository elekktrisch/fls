package ch.alpenflight.me.web;

import ch.alpenflight.me.application.MeService;
import ch.alpenflight.me.application.MeView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    ResponseEntity<MeResponse> get(@AuthenticationPrincipal Jwt jwt) {
        MeView view = meService.resolve(jwt);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(MeResponse.from(view));
    }
}
