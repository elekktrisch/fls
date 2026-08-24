package ch.alpenflight.tenancy.sandbox.web;

import ch.alpenflight.platform.web.ClientIpResolver;
import ch.alpenflight.tenancy.sandbox.application.DemoSessionStarter;
import ch.alpenflight.tenancy.sandbox.application.DemoSessionStarter.StartedDemoSession;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "demo-session",
        description = "Anonymous entry to the sandbox demo. Leases one seat of a fixed pool.")
class DemoSessionController {

    static final String PROBLEM_JSON_THE_HANDLER_ANSWERS_503_WITH = "application/problem+json";

    record DemoSessionResponse(String accessToken, long expiresInSeconds, Instant leaseExpiresAt) {
    }

    private final DemoSessionStarter demoSessions;
    private final ClientIpResolver clientIps;

    DemoSessionController(DemoSessionStarter demoSessions, ClientIpResolver clientIps) {
        this.demoSessions = demoSessions;
        this.clientIps = clientIps;
    }

    @Operation(operationId = "startDemoSession",
            summary = "Lease one free demo seat and return that seat's access token.",
            description = "The caller needs no account. The endpoint creates nothing: it leases a "
                    + "seat of the fixed sandbox pool. One address holds at most one live seat.")
    @ApiResponse(responseCode = "200",
            description = "A seat is leased; the body carries that seat's access token.")
    @ApiResponse(responseCode = "503",
            description = "No seat is free, the address already holds a live seat, or the identity "
                    + "provider issued no token. The problem detail carries a readable reason.",
            content = @Content(mediaType = PROBLEM_JSON_THE_HANDLER_ANSWERS_503_WITH,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(path = "/api/v1/public/demo-session",
            produces = MediaType.APPLICATION_JSON_VALUE)
    DemoSessionResponse startDemoSession(HttpServletRequest request) {
        StartedDemoSession started = demoSessions.startFor(clientIps.resolve(request));
        return new DemoSessionResponse(
                started.accessToken(), started.expiresInSeconds(), started.leaseExpiresAt());
    }
}
