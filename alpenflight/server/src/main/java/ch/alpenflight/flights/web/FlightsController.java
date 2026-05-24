package ch.alpenflight.flights.web;

import ch.alpenflight.flights.application.FlightDtos.FlightCreateRequest;
import ch.alpenflight.flights.application.FlightDtos.FlightDetail;
import ch.alpenflight.flights.application.FlightDtos.FlightListResponse;
import ch.alpenflight.flights.application.FlightDtos.FlightUpdateRequest;
import ch.alpenflight.flights.application.FlightsService;
import ch.alpenflight.platform.id.FlightId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for the Flight aggregate. Per ADR 0005 the path is
 * {@code /api/v1/flights}.
 *
 * <p>Flight is <strong>tenant-scoped</strong> via Hibernate's
 * {@code @TenantId} discriminator on {@code Flight.operatingClubId}. Reads
 * and writes are structurally filtered to the caller's tenant; cross-tenant
 * access surfaces as 404 (IDOR contract).
 *
 * <p>Role gates (per S-159 — no {@code SYSTEM_ADMINISTRATOR} on
 * tenant-scoped endpoints): {@code CLUB_ADMINISTRATOR} or
 * {@code FLIGHT_OPERATOR} for read / create / update;
 * {@code CLUB_ADMINISTRATOR} only for soft-delete (destructive, higher
 * bar — flip the gate when an operator workflow requires it).
 *
 * <p>List endpoint uses keyset cursor pagination (infinite-scroll FE).
 * Default window is the last 90 days; default page size 50, max 200.
 */
@RestController
@RequestMapping(path = "/api/v1/flights", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "flights", description = "Flight CRUD")
class FlightsController {

    private final FlightsService flights;

    FlightsController(FlightsService flights) {
        this.flights = flights;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('CLUB_ADMINISTRATOR', 'FLIGHT_OPERATOR')")
    @Operation(summary = "List flights (keyset-cursor paginated)")
    FlightListResponse list(@RequestParam(value = "from", required = false) @Nullable LocalDate from,
                            @RequestParam(value = "to", required = false) @Nullable LocalDate to,
                            @RequestParam(value = "after", required = false) @Nullable String cursor,
                            @RequestParam(value = "limit", required = false) @Nullable Integer limit) {
        return flights.listFlights(from, to, cursor, limit);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('CLUB_ADMINISTRATOR', 'FLIGHT_OPERATOR')")
    @Operation(summary = "Get a flight by id")
    FlightDetail get(@PathVariable("id") FlightId id) {
        return flights.getFlight(id);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('CLUB_ADMINISTRATOR', 'FLIGHT_OPERATOR')")
    @Operation(summary = "Create a flight")
    ResponseEntity<FlightDetail> create(@Valid @RequestBody FlightCreateRequest req) {
        FlightDetail created = flights.createFlight(req);
        URI location = URI.create("/api/v1/flights/" + created.id().toExternal());
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('CLUB_ADMINISTRATOR', 'FLIGHT_OPERATOR')")
    @Operation(summary = "Update a flight (full replace of editable surface + crew)")
    FlightDetail update(@PathVariable("id") FlightId id,
                        @Valid @RequestBody FlightUpdateRequest req) {
        return flights.updateFlight(id, req);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    @Operation(summary = "Soft-delete a flight")
    ResponseEntity<Void> delete(@PathVariable("id") FlightId id) {
        flights.softDeleteFlight(id);
        return ResponseEntity.noContent().build();
    }
}
