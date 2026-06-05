package ch.alpenflight.me.web;

import ch.alpenflight.flights.application.FlightsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/me/club-dashboard} — the club-admin dashboard variant's
 * tile counts (J-3 T-08, consumed by T-09's club-admin variant component).
 *
 * <p>Lives in the {@code me} module (dashboard-oriented, alongside
 * {@code /api/v1/me} + {@code /api/v1/me/events}) and reads its numbers through
 * the {@code flights} module's published application API
 * ({@link FlightsService#clubFlightCounts()}) rather than reaching into flights
 * internals — the dependency is {@code me}&rarr;{@code flights}, matching the
 * existing {@code FlightCreatedSseListener}.
 *
 * <p>Authz: {@code CLUB_ADMINISTRATOR}-gated — the dashboard variant is
 * admin-only (a pilot / non-admin is rejected with 403). Tenant scoping is
 * automatic: the counts ride the {@code @TenantId} discriminator on
 * {@link ch.alpenflight.flights.domain.Flight}, so they only ever count the
 * caller's own club.
 */
@RestController
@Tag(name = "me", description = "Authenticated-principal view")
class ClubDashboardController {

    private final FlightsService flightsService;

    ClubDashboardController(FlightsService flightsService) {
        this.flightsService = flightsService;
    }

    @GetMapping(path = "/api/v1/me/club-dashboard", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    @Operation(summary = "Club-admin dashboard tile counts — today's club flights + "
            + "flights-pending-validation (NotProcessed + Invalid), tenant-scoped to the "
            + "caller's club. CLUB_ADMINISTRATOR only.")
    ResponseEntity<ClubDashboardResponse> get() {
        ClubDashboardResponse body = ClubDashboardResponse.from(flightsService.clubFlightCounts());
        // Per-club aggregate behind a bearer-bound endpoint — no-store so an
        // intermediary cache can't serve one club's counts to another.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(body);
    }
}
