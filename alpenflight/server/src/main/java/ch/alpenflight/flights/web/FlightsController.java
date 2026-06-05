package ch.alpenflight.flights.web;

import ch.alpenflight.flights.application.FlightDtos.FlightCreateRequest;
import ch.alpenflight.flights.application.FlightDtos.FlightDetail;
import ch.alpenflight.flights.application.FlightDtos.FlightLastContextResponse;
import ch.alpenflight.flights.application.FlightDtos.FlightListResponse;
import ch.alpenflight.flights.application.FlightDtos.FlightProcessStateChangeRequest;
import ch.alpenflight.flights.application.FlightDtos.FlightProcessStateResponse;
import ch.alpenflight.flights.application.FlightDtos.FlightTemplateResponse;
import ch.alpenflight.flights.application.FlightDtos.FlightUpdateRequest;
import ch.alpenflight.flights.application.FlightStateTransitionService;
import ch.alpenflight.flights.application.FlightsService;
import ch.alpenflight.flights.domain.Flight;
import ch.alpenflight.flights.domain.FlightAircraftType;
import ch.alpenflight.flights.domain.TransitionTrigger;
import ch.alpenflight.platform.id.AircraftId;
import ch.alpenflight.platform.id.FlightId;
import ch.alpenflight.platform.id.PersonId;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
    private final FlightStateTransitionService stateService;

    FlightsController(FlightsService flights, FlightStateTransitionService stateService) {
        this.flights = flights;
        this.stateService = stateService;
    }

    // PILOT is granted READ on list + get (J-3): the home dashboard's
    // "Your last flight" card (S-165) is a core PILOT surface that fetches
    // `/flights?personId=<self>&limit=1` then `/flights/{id}`. Reads stay
    // tenant-scoped by @TenantId, so a PILOT sees only their own club's
    // flights — the same row set a FLIGHT_OPERATOR sees. Create / update /
    // soft-delete remain CLUB_ADMINISTRATOR / FLIGHT_OPERATOR only (S-159):
    // PILOT is read-only here.
    @GetMapping
    @PreAuthorize("hasAnyRole('CLUB_ADMINISTRATOR', 'FLIGHT_OPERATOR', 'PILOT')")
    @Operation(summary = "List flights (keyset-cursor paginated). When `personId` is supplied "
            + "(prefixed `pn-<uuid>` per ADR 0019), rows are filtered to flights with a "
            + "non-deleted FlightCrew row for that person, and the sort order is the "
            + "AC-defined `flight_date DESC, start_date_time DESC NULLS LAST, created_on DESC` "
            + "(the third key tie-breaks via UUIDv7 id, which is monotonic-in-creation-time).")
    FlightListResponse list(@RequestParam(value = "from", required = false) @Nullable LocalDate from,
                            @RequestParam(value = "to", required = false) @Nullable LocalDate to,
                            @RequestParam(value = "after", required = false) @Nullable String cursor,
                            @RequestParam(value = "limit", required = false) @Nullable Integer limit,
                            @RequestParam(value = "personId", required = false) @Nullable PersonId personId) {
        return flights.listFlights(from, to, cursor, limit,
                personId == null ? null : personId.value());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('CLUB_ADMINISTRATOR', 'FLIGHT_OPERATOR', 'PILOT')")
    @Operation(summary = "Get a flight by id")
    FlightDetail get(@PathVariable("id") FlightId id) {
        return flights.getFlight(id);
    }

    @GetMapping("/new-template")
    @PreAuthorize("hasAnyRole('CLUB_ADMINISTRATOR', 'FLIGHT_OPERATOR')")
    @Operation(summary = "Empty new-flight template (no id; per-club defaults applied if any)")
    FlightTemplateResponse newTemplate(
            @RequestParam(value = "type", required = false, defaultValue = "GLIDER")
            FlightAircraftType type) {
        return flights.newTemplate(type);
    }

    @GetMapping("/{id}/copy-template")
    @PreAuthorize("hasAnyRole('CLUB_ADMINISTRATOR', 'FLIGHT_OPERATOR')")
    @Operation(summary = "Copy-template projection: source flight minus identity, times, comments, counters")
    FlightTemplateResponse copyTemplate(@PathVariable("id") FlightId id) {
        return flights.copyTemplate(id);
    }

    @GetMapping("/last-context")
    @PreAuthorize("hasAnyRole('CLUB_ADMINISTRATOR', 'FLIGHT_OPERATOR')")
    @Operation(summary = "Last-flight context for (aircraft, date) — mobile-first form pre-fill (AC-DIR-1)")
    ResponseEntity<FlightLastContextResponse> lastContext(
            @RequestParam("aircraftId") AircraftId aircraftId,
            @RequestParam("date") LocalDate date) {
        return flights.lastContext(aircraftId.value(), date)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
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
    @Operation(summary = "Update a flight (full replace of editable surface + crew). If-Match: optimistic-concurrency precondition (RFC 7232 §3.1). On stale: 412 application/problem+json with serverVersion.")
    FlightDetail update(@PathVariable("id") FlightId id,
                        @RequestHeader(value = "If-Match", required = false) @Nullable String ifMatch,
                        @Valid @RequestBody FlightUpdateRequest req) {
        Long expected = parseIfMatch(ifMatch);
        return flights.updateFlight(id, req, expected);
    }

    private static @Nullable Long parseIfMatch(@Nullable String header) {
        if (header == null) {
            return null;
        }
        String trimmed = header.trim();
        if (trimmed.isEmpty() || "*".equals(trimmed)) {
            // RFC 7232 §3.1 — "*" matches any current representation; treat
            // as "no precondition" since every PUT loads the row anyway.
            return null;
        }
        // Strip optional weak / strong ETag wrapping ("123" or W/"123").
        if (trimmed.startsWith("W/")) {
            trimmed = trimmed.substring(2).trim();
        }
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("If-Match header must be a numeric version", e);
        }
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    @Operation(summary = "Soft-delete a flight. If-Match: optional precondition matching PUT semantics; omit to force-delete.")
    ResponseEntity<Void> delete(@PathVariable("id") FlightId id,
                                @RequestHeader(value = "If-Match", required = false) @Nullable String ifMatch) {
        Long expected = parseIfMatch(ifMatch);
        flights.softDeleteFlight(id, expected);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping(path = "/{id}/process-state", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('CLUB_ADMINISTRATOR', 'FLIGHT_OPERATOR')")
    @Operation(summary = "Transition a flight's process state (OPERATOR trigger)")
    FlightProcessStateResponse transitionProcessState(
            @PathVariable("id") FlightId id,
            @Valid @RequestBody FlightProcessStateChangeRequest req) {
        Flight saved = stateService.transition(id, req.processState(), TransitionTrigger.OPERATOR);
        return new FlightProcessStateResponse(
                FlightId.of(java.util.Objects.requireNonNull(saved.getId())),
                saved.getProcessState(),
                saved.getProcessStateId(),
                saved.getVersion());
    }
}
