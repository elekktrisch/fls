package ch.alpenflight.flighttypes.web;

import ch.alpenflight.flighttypes.application.FlightCostBalanceTypeDtos.FlightCostBalanceTypeResponse;
import ch.alpenflight.flighttypes.application.FlightCostBalanceTypesService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/flight-cost-balance-types",
        produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "FlightCostBalanceTypes",
        description = "Flight-cost-balance reference catalog (cross-tenant).")
public class FlightCostBalanceTypesController {

    private final FlightCostBalanceTypesService service;

    public FlightCostBalanceTypesController(FlightCostBalanceTypesService service) {
        this.service = service;
    }

    @Operation(summary = "List all flight-cost-balance types, ordered by legacy_int_id.")
    @ApiResponse(responseCode = "200",
            description = "Array of FlightCostBalanceType reference projections.")
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<FlightCostBalanceTypeResponse> listFlightCostBalanceTypes() {
        return service.listAll();
    }
}
