package ch.alpenflight.clubs.web;

import ch.alpenflight.clubs.application.DiscoveryFlightDayDtos.DiscoveryFlightDayCreateRequest;
import ch.alpenflight.clubs.application.DiscoveryFlightDayDtos.DiscoveryFlightDayResponse;
import ch.alpenflight.clubs.application.DiscoveryFlightDayService;
import ch.alpenflight.platform.tenancy.UserPrincipalLookup;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Club-admin management of the days the club offers discovery flights on — the
 * write surface behind the anonymous picker at
 * {@code GET /api/v1/public/clubs/{clubSlug}/discovery-flight-days}.
 *
 * <p>This resource is deliberately NOT under {@code /api/v1/public/**} — that
 * prefix is {@code permitAll} and would hand day management to anonymous
 * callers. Every method here requires an authenticated CLUB_ADMINISTRATOR, and
 * the club it manages is the principal's own: the path carries no club id at
 * all, so there is nothing to gate a SpEL own-club predicate against and
 * nothing to point at another tenant. Tenancy is structural — Hibernate's
 * {@code @TenantId} discriminator on {@code DiscoveryFlightDay.clubId} scopes
 * the list, stamps the insert, and hides another club's day from withdrawal
 * (404, not 403 — the IDOR contract). Same shape as
 * {@code FlightTypesController} / {@code LocationsController}; SYSTEM_ADMINISTRATOR
 * has no rights here because a sysadmin carries no tenant context.
 */
@RestController
@RequestMapping(path = "/api/v1/discovery-flight-days", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "DiscoveryFlightDays",
        description = "Club-admin CRUD for the club's published discovery-flight days.")
public class DiscoveryFlightDaysController {

    private final DiscoveryFlightDayService service;
    private final UserPrincipalLookup userLookup;

    public DiscoveryFlightDaysController(DiscoveryFlightDayService service,
                                         UserPrincipalLookup userLookup) {
        this.service = service;
        this.userLookup = userLookup;
    }

    @Operation(operationId = "listDiscoveryFlightDays",
            summary = "The caller's club's published discovery-flight days, ascending.")
    @ApiResponse(responseCode = "200",
            description = "Days the club currently offers, past ones included.")
    @GetMapping
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public List<DiscoveryFlightDayResponse> listDiscoveryFlightDays() {
        return service.listDays();
    }

    @Operation(operationId = "publishDiscoveryFlightDay",
            summary = "Offer a new discovery-flight day for the caller's club.")
    @ApiResponse(responseCode = "201", description = "Published; Location names the new day.")
    @ApiResponse(responseCode = "400", description = "The day is missing or lies in the past.")
    @ApiResponse(responseCode = "409", description = "The club already offers that day.")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public ResponseEntity<DiscoveryFlightDayResponse> publishDiscoveryFlightDay(
            @Valid @RequestBody DiscoveryFlightDayCreateRequest req) {
        DiscoveryFlightDayResponse created = service.publishDay(req.eventDate());
        return ResponseEntity
                .created(URI.create("/api/v1/discovery-flight-days/" + created.id()))
                .body(created);
    }

    @Operation(operationId = "withdrawDiscoveryFlightDay",
            summary = "Withdraw a day; the date becomes available to publish again.")
    @ApiResponse(responseCode = "204", description = "Withdrawn.")
    @ApiResponse(responseCode = "404",
            description = "No live day with that id in the caller's club.")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public ResponseEntity<Void> withdrawDiscoveryFlightDay(
            @PathVariable UUID id, @AuthenticationPrincipal @Nullable Jwt jwt) {
        service.withdrawDay(id, principalUserId(jwt));
        return ResponseEntity.noContent().build();
    }

    private @Nullable UUID principalUserId(@Nullable Jwt jwt) {
        if (jwt == null) {
            return null;
        }
        return userLookup.resolveUserIdFor(jwt).orElse(null);
    }
}
