package ch.alpenflight.accounting.web;

import ch.alpenflight.accounting.application.DeliveriesService;
import ch.alpenflight.accounting.application.DeliveryBookingService;
import ch.alpenflight.accounting.application.DeliveryCreationService;
import ch.alpenflight.accounting.application.DeliveryDeletionService;
import ch.alpenflight.accounting.application.DeliveryDtos.DeliveryBookingRequest;
import ch.alpenflight.accounting.application.DeliveryDtos.DeliveryDetail;
import ch.alpenflight.accounting.application.DeliveryDtos.DeliveryPage;
import ch.alpenflight.audit.domain.ReadOnlyQuery;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/deliveries", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Deliveries",
        description = "Read-only delivery (invoice-draft) viewer — tenant-scoped; paged list + view-by-id.")
public class DeliveriesController {

    private final DeliveriesService service;
    private final DeliveryCreationService creationService;
    private final DeliveryDeletionService deletionService;
    private final DeliveryBookingService bookingService;

    public DeliveriesController(DeliveriesService service,
                                DeliveryCreationService creationService,
                                DeliveryDeletionService deletionService,
                                DeliveryBookingService bookingService) {
        this.service = service;
        this.creationService = creationService;
        this.deletionService = deletionService;
        this.bookingService = bookingService;
    }

    @Operation(operationId = "createDeliveries",
            summary = "Create deliveries from all eligible Locked flights (engine -> persist). "
                    + "Returns the created deliveries; empty when none are eligible.")
    @ApiResponse(responseCode = "200", description = "The created deliveries (one per eligible flight).")
    @PostMapping("/create")
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public List<DeliveryDetail> createDeliveries() {
        return creationService.createFromEligibleFlights();
    }

    @Operation(operationId = "bookDelivery",
            summary = "Book a Prepared delivery as delivered (stamp number/date, flip flight+tow to Booked). "
                    + "Returns true on success, false for an unknown id; 409 if already booked.")
    @ApiResponse(responseCode = "200", description = "true when booked, false for an unknown / cross-tenant id.")
    @ApiResponse(responseCode = "409", description = "The delivery is already booked (terminal); no mutation.")
    @PostMapping("/delivered")
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public boolean bookDelivery(@Valid @RequestBody DeliveryBookingRequest request) {
        return bookingService.book(request.deliveryId(), request.deliveryDateTime(), request.deliveryNumber());
    }

    @Operation(operationId = "pageDeliveries",
            summary = "Paged delivery list (SPA-compat page shape). start is a 0-based row offset; "
                    + "size defaults to 100, capped at 500. Rows sorted batch desc / recipient asc.")
    @ApiResponse(responseCode = "200", description = "One page of list rows + totalRows.")
    @PostMapping(path = "/page/{start}/{size}")
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    @ReadOnlyQuery
    public DeliveryPage pageDeliveries(@PathVariable int start, @PathVariable int size) {
        return service.page(start, size);
    }

    @Operation(operationId = "getDelivery",
            summary = "View a single delivery by id (read-only line items, frozen recipient, flight link).")
    @ApiResponse(responseCode = "200", description = "Delivery detail projection.")
    @ApiResponse(responseCode = "404",
            description = "No active delivery with that id (includes cross-tenant lookup).")
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public DeliveryDetail getDelivery(@PathVariable UUID id) {
        return service.getDetail(id);
    }

    @Operation(operationId = "deleteDelivery",
            summary = "Delete a delivery — cascades items, resets the flight (+ tow) to Locked, "
                    + "reverses the consumed credit. 409 when >1 delivery shares the flight.")
    @ApiResponse(responseCode = "204", description = "The delivery was deleted.")
    @ApiResponse(responseCode = "404", description = "No active delivery with that id (includes cross-tenant).")
    @ApiResponse(responseCode = "409", description = "More than one delivery shares the flight; no mutation.")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDelivery(@PathVariable UUID id) {
        deletionService.delete(id);
    }
}
