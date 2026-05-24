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

/**
 * Read-only REST surface for the system-global FlightCostBalanceType
 * catalogue. Rows are Flyway-managed (V3 seed — 5 rows: PILOT_PAYS_ALL,
 * FIFTY_FIFTY_PILOT_COPILOT, TOW_PILOT_PAYS_TOW, NO_INSTRUCTOR_FEE,
 * INVOICE_TO_PERSON).
 *
 * <p>Open to any authenticated principal (S-047 cross-tenant reference
 * pattern) — needed by the FE picker on the FlightType edit screen and by
 * S-058 Flight / S-072 AccountingRuleFilter creation forms. Sysadmin admin
 * CRUD at {@code /api/v1/admin/flight-cost-balance-types/**} is deferred
 * per S-053 design notes; no current consumer demands it.
 */
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
