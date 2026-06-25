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

/**
 * REST surface for the {@code Delivery} aggregate — the invoice-draft viewer plus
 * the create write path, replacing the legacy {@code masterdata/deliveries/}.
 * {@code POST /create} runs the rules engine over eligible flights and persists
 * one delivery each; the read endpoints serve the list + detail. Book / delete are
 * separate write endpoints.
 *
 * <p>Delivery is <strong>tenant-scoped</strong> via Hibernate's {@code @TenantId}
 * discriminator on {@code Delivery.operatingClubId} (ADR 0008). A CLUB_ADMINISTRATOR
 * of A asking for B's delivery receives a {@code 404 Not Found} — the row is
 * invisible under A's scope, not a {@code 403} that would confirm it exists.
 *
 * <p>Role gate: CLUB_ADMINISTRATOR (the deliveries surface is a club-admin
 * masterdata viewer). The Proffix machine-client role is a deferred follow-up,
 * NOT wired here.
 *
 * <p>The paged list is a read-shaped POST ({@code page/{start}/{size}}, the house
 * SPA-paging convention) carrying {@link ReadOnlyQuery} so it is exempt from the
 * mutating-verb audit guard — it changes no state and emits no audit event. Each
 * endpoint carries an explicit {@code @Operation(operationId=...)} so orval
 * generates stable named client methods.
 */
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

    /**
     * Runs the rules engine over every eligible flight (Locked, glider/motor,
     * {@code created_on <= today - 3d}) in the caller's tenant and persists one
     * delivery per flight, returning the created deliveries. Mutating POST (replaces
     * the legacy mutating {@code GET /deliveries/create} quirk). Per-flight failures
     * are swallowed; the batch never aborts.
     */
    @Operation(operationId = "createDeliveries",
            summary = "Create deliveries from all eligible Locked flights (engine -> persist). "
                    + "Returns the created deliveries; empty when none are eligible.")
    @ApiResponse(responseCode = "200", description = "The created deliveries (one per eligible flight).")
    @PostMapping("/create")
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public List<DeliveryDetail> createDeliveries() {
        return creationService.createFromEligibleFlights();
    }

    /**
     * Books a Prepared delivery as delivered (the external finance / Proffix
     * confirmation): stamps the request-supplied delivery number + delivered
     * timestamp, marks the delivery Booked, and flips the linked flight (+ tow) to
     * {@code DeliveryBooked}. Returns {@code true} on success, {@code false} when no
     * active delivery with that id exists in the caller's tenant (legacy parity — an
     * unknown id is not an error). A delivery already booked is terminal → {@code 409}.
     */
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

    /**
     * SPA paged list — the house {@code POST .../page/{start}/{size}} shape.
     * {@code start} is a 0-based ROW offset; {@code size} defaults to 100 and is
     * capped at 500 by the service. Response is the camelCase {@code {items,
     * pageStart, pageSize, totalRows}} envelope; rows are sorted batch desc /
     * recipient asc (the legacy order), tenant-scoped via {@code @TenantId}.
     * Read-shaped POST so it carries {@link ReadOnlyQuery}.
     */
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

    /**
     * Deletes a Prepared delivery (club-admin): it + its line items disappear, the
     * linked flight AND its tow reset to {@code Locked} (persisted), and any
     * flight-time credit the create consumed is reversed (append-only compensating
     * row). Rejected with {@code 409} when more than one delivery shares the flight
     * (no partial mutation); {@code 404} when the id is unknown or cross-tenant.
     */
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
