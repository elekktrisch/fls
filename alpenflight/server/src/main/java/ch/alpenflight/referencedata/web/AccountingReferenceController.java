package ch.alpenflight.referencedata.web;

import ch.alpenflight.referencedata.application.ReferenceDataDtos.AccountingRuleFilterTypeResponse;
import ch.alpenflight.referencedata.application.ReferenceDataDtos.AccountingUnitTypeResponse;
import ch.alpenflight.referencedata.application.ReferenceDataDtos.FlightCrewTypeResponse;
import ch.alpenflight.referencedata.application.ReferenceDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only REST surface for the accounting rule-filter edit form's dropdowns:
 * the filter-type, accounting-unit-type, and flight-crew-type reference
 * catalogs. All three are system-global Flyway-seeded reference tables
 * (filter-types + unit-types from V4, crew-types from V3); the API never writes
 * here.
 *
 * <p>Gated {@code isAuthenticated()} to match the reference-endpoint convention
 * (Country / FlightCostBalanceType) — dropdown fuel is read-by-everyone, not
 * admin-only (unlike the {@code AccountingRuleFilter} CRUD surface itself).
 *
 * <p>Each method carries a stable {@code @Operation(operationId=…)} so the
 * orval-generated SPA client gets named methods the J-8 accounting store
 * consumes ({@code listAccountingRuleFilterTypes} / {@code listAccountingUnitTypes}
 * / {@code listFlightCrewTypes}).
 */
@RestController
@Tag(name = "AccountingReferenceData",
        description = "Reference catalogs for the accounting rule-filter edit form (cross-tenant).")
public class AccountingReferenceController {

    private final ReferenceDataService service;

    public AccountingReferenceController(ReferenceDataService service) {
        this.service = service;
    }

    @Operation(operationId = "listAccountingRuleFilterTypes",
            summary = "List accounting rule-filter types, ordered by legacy_int_id. "
                    + "Carries legacyId — the SPA drives section visibility off it.")
    @ApiResponse(responseCode = "200",
            description = "Array of AccountingRuleFilterType reference projections.")
    @GetMapping(path = "/api/v1/accounting-rule-filter-types",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public List<AccountingRuleFilterTypeResponse> listAccountingRuleFilterTypes() {
        return service.listAccountingRuleFilterTypes();
    }

    @Operation(operationId = "listAccountingUnitTypes",
            summary = "List accounting unit types, ordered by legacy_int_id.")
    @ApiResponse(responseCode = "200",
            description = "Array of AccountingUnitType reference projections.")
    @GetMapping(path = "/api/v1/accounting-unit-types",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public List<AccountingUnitTypeResponse> listAccountingUnitTypes() {
        return service.listAccountingUnitTypes();
    }

    @Operation(operationId = "listFlightCrewTypes",
            summary = "List flight-crew types, ordered by legacy_int_id.")
    @ApiResponse(responseCode = "200",
            description = "Array of FlightCrewType reference projections.")
    @GetMapping(path = "/api/v1/flight-crew-types",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public List<FlightCrewTypeResponse> listFlightCrewTypes() {
        return service.listFlightCrewTypes();
    }
}
