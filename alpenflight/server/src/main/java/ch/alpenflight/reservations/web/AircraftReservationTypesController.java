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

/**
 * Reservation-type lookup surface (J-5 T-05) — the type dropdown for the
 * reservation edit form. Split from {@link AircraftReservationsController}
 * because the path {@code /api/v1/aircraft-reservation-types} (the T-01 spec
 * drives this) is a sibling resource, not a sub-path of
 * {@code /api/v1/aircraft-reservations}.
 *
 * <p>Read-only + {@code isAuthenticated()} — any authenticated tenant member
 * may load their tenant's reservation types. No mutating endpoint here (type
 * CRUD is masterdata-admin work outside this CRUD seam), so this controller has
 * no {@code ControllerAuditCoverageTest} obligation.
 */
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
