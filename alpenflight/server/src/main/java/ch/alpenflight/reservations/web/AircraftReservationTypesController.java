package ch.alpenflight.reservations.web;

import ch.alpenflight.reservations.application.AircraftReservationDtos.AircraftReservationTypeListItem;
import ch.alpenflight.reservations.application.AircraftReservationsService;
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
@RequestMapping(path = "/api/v1/aircraft-reservation-types", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Aircraft reservation types",
        description = "Reservation-type listitems for the reservation form dropdown.")
public class AircraftReservationTypesController {

    private final AircraftReservationsService service;

    public AircraftReservationTypesController(AircraftReservationsService service) {
        this.service = service;
    }

    @Operation(operationId = "listAircraftReservationTypes",
            summary = "List active reservation types in the tenant (the type dropdown).")
    @ApiResponse(responseCode = "200", description = "Array of reservation-type listitems.")
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<AircraftReservationTypeListItem> listAircraftReservationTypes() {
        return service.listReservationTypes();
    }
}
