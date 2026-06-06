package ch.alpenflight.reservations.application;

import ch.alpenflight.platform.id.AircraftId;
import ch.alpenflight.platform.id.LocationId;
import ch.alpenflight.platform.id.PersonId;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * DTOs for the {@code AircraftReservation} REST surface (J-5 T-05). Records
 * (immutable, explicit field set); mass-assignment is structurally impossible
 * because the controller binds to the record, not to the
 * {@link ch.alpenflight.reservations.domain.AircraftReservation} aggregate.
 *
 * <p>Field names mirror the legacy {@code AircraftReservationDetails} shape +
 * the T-01 spec stub ({@code aircraftId}, {@code pilotPersonId},
 * {@code locationId}, {@code secondCrewPersonId?}, {@code reservationTypeId} /
 * {@code flightTypeId}, {@code start}, {@code end}, {@code isAllDay}, the
 * free-text {@code remarks} mapped to the aggregate's {@code info}). The
 * cross-aggregate FK references ({@code aircraftId} / {@code pilotPersonId} /
 * {@code secondCrewPersonId} / {@code locationId}) are the typed-id family
 * ({@link AircraftId} / {@link PersonId} / {@link LocationId}) so the wire shape
 * matches the masterdata pickers (which serialize {@code ^ac-…} / {@code ^pn-…} /
 * {@code ^loc-…}) and mirrors {@code FlightCreateRequest} — Jackson deserializes
 * the prefixed strings via {@code TypedIdJacksonModule} (J-5 T-25, resolving the
 * 400 in T-23). The reservation-type references ({@code reservationTypeId} /
 * {@code flightTypeId}) and the reservation's OWN {@code id} stay plain
 * {@link UUID} (those listitems emit plain UUIDs). The aggregate (T-03) and
 * repository (T-04) still carry raw {@code UUID}s; the mapper wraps/unwraps at
 * this boundary.
 *
 * <p>{@code operatingClubId} is intentionally absent from the request records:
 * the reservation is tenant-stamped from the caller's resolved tenant
 * (legacy-open cross-tenant aircraft parity — the operating club is the
 * principal's club, the aircraft FK may cross tenants). A04 mass-assignment
 * defense — a caller cannot re-key the tenant via the request body.
 */
public final class AircraftReservationDtos {

    private AircraftReservationDtos() {}

    @Schema(description = "Aircraft-reservation detail / list projection.")
    public record AircraftReservationDetail(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID operatingClubId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AircraftId aircraftId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) PersonId pilotPersonId,
            @Nullable PersonId secondCrewPersonId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocationId locationId,
            @Nullable UUID reservationTypeId,
            @Nullable UUID flightTypeId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant start,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant end,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isAllDay,
            @Nullable String remarks) {}

    /**
     * Create payload. {@code aircraftId} / {@code pilotPersonId} /
     * {@code locationId} are required; a reservation-type reference is required
     * (either {@code reservationTypeId} or {@code flightTypeId} — validated in
     * the service so the message is specific). {@code start} / {@code end} /
     * {@code isAllDay} are required; a timed reservation with end not after
     * start is rejected 422 by the aggregate's {@code validateDuration}.
     */
    @Schema(description = "Payload to create an aircraft reservation.")
    public record AircraftReservationCreateRequest(
            @NotNull AircraftId aircraftId,
            @NotNull PersonId pilotPersonId,
            @NotNull LocationId locationId,
            @Nullable PersonId secondCrewPersonId,
            @Nullable UUID reservationTypeId,
            @Nullable UUID flightTypeId,
            @NotNull Instant start,
            @NotNull Instant end,
            @NotNull Boolean isAllDay,
            @Nullable @Size(max = 4000) String remarks) {}

    @Schema(description = "Payload to update an aircraft reservation.")
    public record AircraftReservationUpdateRequest(
            @NotNull AircraftId aircraftId,
            @NotNull PersonId pilotPersonId,
            @NotNull LocationId locationId,
            @Nullable PersonId secondCrewPersonId,
            @Nullable UUID reservationTypeId,
            @Nullable UUID flightTypeId,
            @NotNull Instant start,
            @NotNull Instant end,
            @NotNull Boolean isAllDay,
            @Nullable @Size(max = 4000) String remarks) {}

    @Schema(description = "Reservation-type listitem for the type dropdown.")
    public record AircraftReservationTypeListItem(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean active) {}

    /**
     * List-row projection for the paged list / future / day overview reads
     * (J-5 T-06). Carries the reservation's own fields + the FK ids the SPA
     * table needs (aircraft lane, pilot, location, type); display labels
     * (immatriculation, pilot name, location name) are decorated client-side
     * from the picker payloads — the no-cross-module-join convention (ADR 0023),
     * mirroring the Flight {@code FlightListItem} and Aircraft {@code ListRow}.
     * {@code reservationTypeName} is the one same-module decoration (joined from
     * {@code AircraftReservationType}, like Aircraft denormalises its type code).
     */
    @Schema(description = "Aircraft-reservation list row (FK ids + same-module type name; SPA decorates the rest).")
    public record AircraftReservationListItem(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AircraftId aircraftId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant start,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant end,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isAllDay,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) PersonId pilotPersonId,
            @Nullable PersonId secondCrewPersonId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocationId locationId,
            @Nullable UUID reservationTypeId,
            @Nullable String reservationTypeName,
            @Nullable UUID flightTypeId,
            @Nullable String remarks) {}

    /**
     * SPA paged envelope for {@code POST .../page/{start}/{size}}. AlpenFlight
     * house style — camelCase {@code items/pageStart/pageSize/totalRows} (NOT
     * the legacy PascalCase {@code Items/TotalRows}); the T-01 spec stub locks
     * this exact shape. {@code totalRows} is the unpaged tenant-scoped count so
     * the SPA can render pagination without a second round-trip.
     */
    @Schema(description = "Paged aircraft-reservation list envelope (SPA-compat page shape).")
    public record AircraftReservationPage(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<AircraftReservationListItem> items,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int pageStart,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int pageSize,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long totalRows) {}

    /**
     * Legacy {@code PageableSearchFilter}-shaped request body for the paged
     * list. For J-5 scope the load-bearing fields are {@code sorting} (sort by
     * {@code start} asc/desc — default asc) + a basic {@code searchFilter}
     * (date-range narrowing on the reservation start). The legacy filter carries
     * more fields (immatriculation etc.); those need a cross-module join and are
     * deliberately NOT built here (the table decorates immat client-side, so a
     * server-side immat filter isn't load-bearing for the page envelope proof).
     * Both members are optional — an empty body returns the full first page
     * sorted by start asc.
     */
    @Schema(description = "Legacy PageableSearchFilter-shaped paged-list request (sorting + basic filter).")
    public record AircraftReservationPageRequest(
            @Nullable @Schema(description = "Column→direction map; only `start: asc|desc` is honoured (default asc).")
                    Map<String, String> sorting,
            @Nullable AircraftReservationSearchFilter searchFilter) {}

    @Schema(description = "Basic paged-list filter — date-range narrowing on the reservation start (J-5 scope).")
    public record AircraftReservationSearchFilter(
            @Nullable Instant from,
            @Nullable Instant to) {}
}
