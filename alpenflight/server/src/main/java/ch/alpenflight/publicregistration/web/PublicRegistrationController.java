package ch.alpenflight.publicregistration.web;

import ch.alpenflight.publicregistration.application.PublicRegistrationIntake;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The anonymous flight-experience registration surface. No {@code @PreAuthorize}
 * and no principal: the target tenant comes from {@code {clubSlug}}, which
 * {@link PublicRegistrationIntake} resolves and allowlist-checks before anything
 * tenant-scoped runs.
 *
 * <p>Both endpoints currently accept the submission and record it; the registrant
 * payload, its server-side validation and the Person / reservation writes are the
 * registration tasks that take these method bodies over. The paths, the
 * anonymous access and the 404 / 403 contract are settled here.
 */
@RestController
@Tag(name = "public-registration",
        description = "Anonymous discovery- and scenic-flight registration.")
class PublicRegistrationController {

    private final PublicRegistrationIntake intake;
    private final ClientIpResolver clientIps;

    PublicRegistrationController(PublicRegistrationIntake intake, ClientIpResolver clientIps) {
        this.intake = intake;
        this.clientIps = clientIps;
    }

    @Operation(operationId = "submitDiscoveryFlightRegistration",
            summary = "Register anonymously for a club's discovery flight.")
    @ApiResponse(responseCode = "202", description = "Accepted for the resolved club.")
    @ApiResponse(responseCode = "403", description = "The club has closed public registration.")
    @ApiResponse(responseCode = "404", description = "No club is published at that slug.")
    @ApiResponse(responseCode = "429",
            description = "Abuse guard tripped; retry after the Retry-After header.")
    @PostMapping("/api/v1/public/clubs/{clubSlug}/discovery-flight-registrations")
    ResponseEntity<Void> submitDiscoveryFlightRegistration(
            @PathVariable String clubSlug, HttpServletRequest request) {
        intake.acceptDiscovery(clubSlug, clientIps.resolve(request));
        return ResponseEntity.accepted().build();
    }

    @Operation(operationId = "submitScenicFlightRegistration",
            summary = "Register anonymously for a club's scenic flight.")
    @ApiResponse(responseCode = "202", description = "Accepted for the resolved club.")
    @ApiResponse(responseCode = "403", description = "The club has closed public registration.")
    @ApiResponse(responseCode = "404", description = "No club is published at that slug.")
    @ApiResponse(responseCode = "429",
            description = "Abuse guard tripped; retry after the Retry-After header.")
    @PostMapping("/api/v1/public/clubs/{clubSlug}/scenic-flight-registrations")
    ResponseEntity<Void> submitScenicFlightRegistration(
            @PathVariable String clubSlug, HttpServletRequest request) {
        intake.acceptScenic(clubSlug, clientIps.resolve(request));
        return ResponseEntity.accepted().build();
    }
}
