package ch.alpenflight.referencedata.web;

import ch.alpenflight.referencedata.application.ReferenceDataDtos.AircraftTypeResponse;
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

@RestController
@RequestMapping(path = "/api/v1/aircraft-types", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "AircraftTypes", description = "Aircraft-type reference catalog.")
public class AircraftTypeController {

    private final ReferenceDataService service;

    public AircraftTypeController(ReferenceDataService service) {
        this.service = service;
    }

    @Operation(summary = "List all aircraft types, ordered by legacy_int_id.")
    @ApiResponse(responseCode = "200", description = "Array of aircraft-type listitem projections.")
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<AircraftTypeResponse> listAircraftTypes() {
        return service.listAircraftTypes();
    }
}
