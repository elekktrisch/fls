package ch.alpenflight.referencedata.web;

import ch.alpenflight.referencedata.application.ReferenceDataDtos.CountryResponse;
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
 * Read-only REST surface for the system-global Country catalog. Rows are
 * Flyway-managed (V2 seed); the API never writes here. Authenticated
 * users (any role) may read — every SPA form needs the dropdown.
 */
@RestController
@RequestMapping(path = "/api/v1/countries", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Countries", description = "ISO-3166 country catalog.")
public class CountryController {

    private final ReferenceDataService service;

    public CountryController(ReferenceDataService service) {
        this.service = service;
    }

    @Operation(summary = "List all countries, alphabetical under de-CH ICU collation.")
    @ApiResponse(responseCode = "200", description = "Array of country listitem projections.")
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<CountryResponse> listCountries() {
        return service.listCountries();
    }
}
