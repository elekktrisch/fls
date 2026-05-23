package ch.alpenflight.referencedata.web;

import ch.alpenflight.referencedata.application.ReferenceDataDtos.AircraftStateResponse;
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
 * Read-only REST surface for the system-global AircraftState catalog. Rows
 * are Flyway-managed (V3 seed — 7 rows: OK, INFORMATION, ATTENTION,
 * MALFUNCTION, MAINTENANCE, UNINSURED, END_OF_LIFE).
 */
@RestController
@RequestMapping(path = "/api/v1/aircraft-states", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "AircraftStates", description = "Aircraft-state reference catalog.")
public class AircraftStateController {

    private final ReferenceDataService service;

    public AircraftStateController(ReferenceDataService service) {
        this.service = service;
    }

    @Operation(summary = "List all aircraft states, ordered by legacy_int_id.")
    @ApiResponse(responseCode = "200", description = "Array of aircraft-state listitem projections.")
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<AircraftStateResponse> listAircraftStates() {
        return service.listAircraftStates();
    }
}
