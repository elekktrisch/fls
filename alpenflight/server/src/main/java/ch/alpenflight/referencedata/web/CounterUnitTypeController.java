package ch.alpenflight.referencedata.web;

import ch.alpenflight.referencedata.application.ReferenceDataDtos.CounterUnitTypeResponse;
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
 * Read-only REST surface for the system-global CounterUnitType catalog.
 * Rows are Flyway-managed (V2 seed — 4 rows: HOURS_DECIMAL, HOURS_MINUTES,
 * LANDINGS, STARTS). Drives the operating-counter unit dropdowns on the
 * Aircraft Add form (per design notes — counters can't be recorded without
 * the unit set first).
 */
@RestController
@RequestMapping(path = "/api/v1/counter-unit-types", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "CounterUnitTypes", description = "Counter-unit-type reference catalog.")
public class CounterUnitTypeController {

    private final ReferenceDataService service;

    public CounterUnitTypeController(ReferenceDataService service) {
        this.service = service;
    }

    @Operation(summary = "List all counter unit types, ordered by code.")
    @ApiResponse(responseCode = "200",
            description = "Array of counter-unit-type listitem projections.")
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<CounterUnitTypeResponse> listCounterUnitTypes() {
        return service.listCounterUnitTypes();
    }
}
