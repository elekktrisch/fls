package ch.alpenflight.referencedata.web;

import ch.alpenflight.referencedata.application.ReferenceDataDtos.LocationTypeResponse;
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
@RequestMapping(path = "/api/v1/location-types", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "LocationTypes", description = "Location-type reference catalog.")
public class LocationTypeController {

    private final ReferenceDataService service;

    public LocationTypeController(ReferenceDataService service) {
        this.service = service;
    }

    @Operation(summary = "List all location types, ordered by description.")
    @ApiResponse(responseCode = "200", description = "Array of location-type listitem projections.")
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<LocationTypeResponse> listLocationTypes() {
        return service.listLocationTypes();
    }
}
