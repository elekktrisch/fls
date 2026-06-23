package ch.alpenflight.joinrequests.web;

import ch.alpenflight.joinrequests.application.JoinRequestDecisionsService;
import ch.alpenflight.joinrequests.application.JoinRequestDtos.ApproveJoinRequest;
import ch.alpenflight.joinrequests.application.JoinRequestDtos.DenyJoinRequest;
import ch.alpenflight.joinrequests.application.JoinRequestDtos.JoinRequestResponse;
import ch.alpenflight.joinrequests.application.JoinRequestDtos.PendingJoinRequestResponse;
import ch.alpenflight.joinrequests.application.JoinRequestDtos.SubmitJoinRequest;
import ch.alpenflight.joinrequests.application.JoinRequestsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for the pilot self-serve join-request slice (S-178, T-05).
 * Three role bands:
 *
 * <ul>
 *   <li><b>Submit / withdraw / me-read</b> — any authenticated principal. The
 *       caller is a signed-up pilot with no {@code t_user} yet, so there is no
 *       tenant gate; the service resolves the tenant from the join code (submit)
 *       or the pilot's own request (withdraw / me) and enforces sub-ownership.</li>
 *   <li><b>Pending list</b> — CLUB_ADMINISTRATOR, tenant-scoped to the caller's
 *       own club via Hibernate's {@code @TenantId} discriminator.</li>
 * </ul>
 *
 * <p>Approve / deny are the CLUB_ADMINISTRATOR cross-system decision endpoints
 * (T-06). Submit is abuse-guarded (T-07): the brute-force rate limit + the 24h
 * deny cooldown surface as 429 + {@code Retry-After}. Emails / SSE (T-08) are not
 * in this resource yet.
 */
@RestController
@Tag(name = "join-requests", description = "Pilot self-serve club-join requests.")
class JoinRequestController {

    private final JoinRequestsService service;
    private final JoinRequestDecisionsService decisions;

    JoinRequestController(JoinRequestsService service, JoinRequestDecisionsService decisions) {
        this.service = service;
        this.decisions = decisions;
    }

    @Operation(summary = "File a pending join request against a club's join code.")
    @ApiResponse(responseCode = "201", description = "Filed; body is the pending request.")
    @ApiResponse(responseCode = "404", description = "No active club resolves to that code.")
    @ApiResponse(responseCode = "409", description = "Caller already belongs to a club, "
            + "or already has an open request for this club.")
    @ApiResponse(responseCode = "429", description = "Rate limit (5 submit attempts / 15 min "
            + "per principal) or 24h deny cooldown — carries a Retry-After header.")
    @PostMapping(path = "/api/v1/join-requests",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<JoinRequestResponse> submit(@AuthenticationPrincipal Jwt jwt,
                                               @Valid @RequestBody SubmitJoinRequest req) {
        JoinRequestResponse created = service.submit(jwt, req.joinCode(), req.note());
        return ResponseEntity
                .created(URI.create("/api/v1/join-requests/" + created.id()))
                .body(created);
    }

    @Operation(summary = "Withdraw the caller's own pending request.")
    @ApiResponse(responseCode = "200", description = "Withdrawn; body is the request.")
    @ApiResponse(responseCode = "403", description = "The request belongs to another pilot.")
    @ApiResponse(responseCode = "404", description = "No request with that id.")
    @PostMapping(path = "/api/v1/join-requests/{id}/withdraw",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    JoinRequestResponse withdraw(@AuthenticationPrincipal Jwt jwt, @PathVariable java.util.UUID id) {
        return service.withdraw(jwt, id);
    }

    @Operation(summary = "The caller's latest join request, or 204 if none.")
    @ApiResponse(responseCode = "200", description = "The pilot's latest request.")
    @ApiResponse(responseCode = "204", description = "The caller has filed no request.")
    @GetMapping(path = "/api/v1/me/join-request", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<JoinRequestResponse> myJoinRequest(@AuthenticationPrincipal Jwt jwt) {
        return service.latestForCaller(jwt)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @Operation(summary = "The admin's own-club pending join requests.")
    @ApiResponse(responseCode = "200", description = "Pending requests for the caller's club.")
    @GetMapping(path = "/api/v1/join-requests", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    List<PendingJoinRequestResponse> listPending(
            @RequestParam(name = "status", defaultValue = "pending") String status) {
        // Today only the pending list is served; a non-pending filter is a
        // 400 rather than a silent empty list, so a typo surfaces.
        if (!"pending".equalsIgnoreCase(status)) {
            throw new UnsupportedJoinRequestStatusFilterException(status);
        }
        return service.pendingForCurrentTenant();
    }

    @Operation(summary = "Approve a pending request — KC clubId attribute + t_user + Person + roles.")
    @ApiResponse(responseCode = "200", description = "Approved; body is the request.")
    @ApiResponse(responseCode = "403", description = "A role the admin may not grant.")
    @ApiResponse(responseCode = "404", description = "No pending request in the admin's club.")
    @ApiResponse(responseCode = "409", description = "Not pending (re-approve), the caller already has "
            + "a t_user, or the linked Person is in another club.")
    @PostMapping(path = "/api/v1/join-requests/{id}/approve",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    JoinRequestResponse approve(@AuthenticationPrincipal Jwt jwt,
                                @PathVariable java.util.UUID id,
                                @Valid @RequestBody ApproveJoinRequest req) {
        return decisions.approve(jwt, id, req);
    }

    @Operation(summary = "Deny a pending request with an optional reason.")
    @ApiResponse(responseCode = "200", description = "Denied; body is the request.")
    @ApiResponse(responseCode = "404", description = "No pending request in the admin's club.")
    @ApiResponse(responseCode = "409", description = "Not pending.")
    @PostMapping(path = "/api/v1/join-requests/{id}/deny",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    JoinRequestResponse deny(@AuthenticationPrincipal Jwt jwt,
                             @PathVariable java.util.UUID id,
                             @Valid @RequestBody DenyJoinRequest req) {
        return decisions.deny(jwt, id, req);
    }
}
