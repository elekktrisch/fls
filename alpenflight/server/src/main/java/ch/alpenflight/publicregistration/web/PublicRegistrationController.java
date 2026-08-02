package ch.alpenflight.publicregistration.web;

import ch.alpenflight.platform.id.PersonId;
import ch.alpenflight.publicregistration.application.PublicRegistrationDtos.DiscoveryFlightRegistrationRequest;
import ch.alpenflight.publicregistration.application.PublicRegistrationDtos.DiscoveryFlightRegistrationResponse;
import ch.alpenflight.publicregistration.application.PublicRegistrationDtos.ScenicFlightRegistrationRequest;
import ch.alpenflight.publicregistration.application.PublicRegistrationDtos.ScenicFlightRegistrationResponse;
import ch.alpenflight.publicregistration.application.PublicRegistrationIntake;
import ch.alpenflight.publicregistration.application.PublicRegistrationIntake.Accepted;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The anonymous flight-experience registration surface. No {@code @PreAuthorize}
 * and no principal: the target tenant comes from {@code {clubSlug}}, which
 * {@link PublicRegistrationIntake} resolves and allowlist-checks before anything
 * tenant-scoped runs.
 *
 * <p>Both POSTs nest the same
 * {@link ch.alpenflight.publicregistration.application.PublicRegistrantDetails}:
 * the two forms differ only in the day selection, so the registrant contract is
 * one type and cannot drift between them.
 *
 * <p>{@code Location} on the 201 names the registrant's {@code Person} — the
 * durable resource an accepted submission creates. It is not anonymously
 * readable, and deliberately so: the header says where the row lives, the club
 * says who may read it.
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

    @Operation(operationId = "listPublicDiscoveryFlightDays",
            summary = "The days a club still offers discovery flights on.")
    @ApiResponse(responseCode = "200",
            description = "Bookable days ascending; empty when the club has published none.")
    @ApiResponse(responseCode = "403", description = "The club has closed public registration.")
    @ApiResponse(responseCode = "404", description = "No club is published at that slug.")
    @GetMapping(path = "/api/v1/public/clubs/{clubSlug}/discovery-flight-days",
            produces = MediaType.APPLICATION_JSON_VALUE)
    List<LocalDate> listPublicDiscoveryFlightDays(@PathVariable String clubSlug) {
        return intake.bookableDiscoveryDays(clubSlug);
    }

    @Operation(operationId = "submitDiscoveryFlightRegistration",
            summary = "Register anonymously for a club's discovery flight.")
    @ApiResponse(responseCode = "201", description = "Registered; Location names the registrant.")
    @ApiResponse(responseCode = "400",
            description = "The registrant field contract failed, or the day is not one the "
                    + "club still offers.")
    @ApiResponse(responseCode = "403", description = "The club has closed public registration.")
    @ApiResponse(responseCode = "404", description = "No club is published at that slug.")
    @ApiResponse(responseCode = "429",
            description = "Abuse guard tripped; retry after the Retry-After header.")
    @PostMapping(path = "/api/v1/public/clubs/{clubSlug}/discovery-flight-registrations",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<DiscoveryFlightRegistrationResponse> submitDiscoveryFlightRegistration(
            @PathVariable String clubSlug,
            @RequestBody DiscoveryFlightRegistrationRequest body,
            HttpServletRequest request) {
        Accepted accepted = intake.acceptDiscovery(clubSlug, clientIps.resolve(request),
                body.registrant(), body.selectedDay());
        PersonId registrant = PersonId.of(accepted.registered().registrantPersonId());
        return ResponseEntity
                .created(URI.create("/api/v1/persons/" + registrant.toExternal()))
                .body(new DiscoveryFlightRegistrationResponse(
                        registrant, accepted.club().clubName(), body.selectedDay()));
    }

    @Operation(operationId = "submitScenicFlightRegistration",
            summary = "Register anonymously for a club's scenic flight.")
    @ApiResponse(responseCode = "201", description = "Registered; Location names the registrant.")
    @ApiResponse(responseCode = "400",
            description = "The registrant field contract failed, or the submission carried a "
                    + "member this flow has no meaning for — the scenic form selects no day.")
    @ApiResponse(responseCode = "403", description = "The club has closed public registration.")
    @ApiResponse(responseCode = "404", description = "No club is published at that slug.")
    @ApiResponse(responseCode = "429",
            description = "Abuse guard tripped; retry after the Retry-After header.")
    @PostMapping(path = "/api/v1/public/clubs/{clubSlug}/scenic-flight-registrations",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ScenicFlightRegistrationResponse> submitScenicFlightRegistration(
            @PathVariable String clubSlug,
            @RequestBody ScenicFlightRegistrationRequest body,
            HttpServletRequest request) {
        Accepted accepted = intake.acceptScenic(
                clubSlug, clientIps.resolve(request), body.registrant());
        PersonId registrant = PersonId.of(accepted.registered().registrantPersonId());
        return ResponseEntity
                .created(URI.create("/api/v1/persons/" + registrant.toExternal()))
                .body(new ScenicFlightRegistrationResponse(
                        registrant, accepted.club().clubName()));
    }
}
