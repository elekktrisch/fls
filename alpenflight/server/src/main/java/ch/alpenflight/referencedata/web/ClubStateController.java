package ch.alpenflight.referencedata.web;

import ch.alpenflight.referencedata.application.ReferenceDataDtos.ClubStateResponse;
import ch.alpenflight.referencedata.application.ReferenceDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only REST surface for the system-global ClubState catalog.
 */
@RestController
@RequestMapping(path = "/api/v1/club-states", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "ClubStates", description = "Club lifecycle-state catalog.")
public class ClubStateController {

    private final ReferenceDataService service;

    public ClubStateController(ReferenceDataService service) {
        this.service = service;
    }

    @Operation(summary = "List all club states, alphabetical under de-CH ICU collation.")
    @ApiResponse(responseCode = "200", description = "Array of club-state listitem projections.")
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<ClubStateResponse> listClubStates() {
        return service.listClubStates();
    }
}
