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
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(body);
    }
}
