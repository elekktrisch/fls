package ch.alpenflight.migrations.web;

import ch.alpenflight.migrations.application.MigrationHandshakeService;
import ch.alpenflight.migrations.application.MigrationUploadView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for the S-140 per-upload keypair handshake.
 *
 * <p>Both endpoints gated on {@code isAuthenticated() and
 * principal.claims['email_verified'] == true}:
 *
 * <ul>
 *   <li>{@code POST /api/v1/migrations/handshake} — mint a fresh
 *       RSA-4096 keypair, persist the wrapped private key, return the
 *       public PEM + expiry. Silent supersede of any prior in-flight
 *       row for the same user.</li>
 *   <li>{@code GET /api/v1/migrations/handshake/current} — SPA mount
 *       restore. Returns the caller's current {@code awaiting_upload}
 *       row (sans private key) or 404.</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/api/v1/migrations/handshake", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Migration handshake",
        description = "Per-upload keypair handshake — public-key surface for the legacy export JAR (S-140).")
public class MigrationHandshakeController {

    /**
     * SpEL gate shared by both endpoints. Declared as a constant so the
     * predicate (and its rationale) lives in one place.
     */
    private static final String EMAIL_VERIFIED =
            "isAuthenticated() and principal.claims['email_verified'] == true";

    private final MigrationHandshakeService service;

    public MigrationHandshakeController(MigrationHandshakeService service) {
        this.service = service;
    }

    @Operation(summary = "Issue a fresh per-upload RSA-4096 keypair. Supersedes any prior in-flight row.")
    @ApiResponse(responseCode = "200", description = "Handshake row created or replaced.")
    @PreAuthorize(EMAIL_VERIFIED)
    @PostMapping
    public HandshakeResponse issue(@AuthenticationPrincipal Jwt jwt) {
        MigrationUploadView view = service.issue(jwt);
        return HandshakeResponse.of(view);
    }

    @Operation(summary = "Restore the caller's current in-flight handshake row (SPA mount path).")
    @ApiResponse(responseCode = "200", description = "Caller has an in-flight handshake row.")
    @ApiResponse(responseCode = "404", description = "No in-flight handshake row for the caller.")
    @PreAuthorize(EMAIL_VERIFIED)
    @GetMapping("/current")
    public ResponseEntity<HandshakeResponse> current(@AuthenticationPrincipal Jwt jwt) {
        Optional<MigrationUploadView> view = service.findCurrent(jwt);
        return view.map(HandshakeResponse::of)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
