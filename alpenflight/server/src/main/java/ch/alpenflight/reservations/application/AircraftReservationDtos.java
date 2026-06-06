package ch.alpenflight.reservations.application;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
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
 * free-text {@code remarks} mapped to the aggregate's {@code info}). Ids are
 * plain {@link UUID} — the aggregate (T-03) and repository (T-04) carry raw
 * {@code UUID}s, so no typed-id / path-converter plumbing is introduced here.
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
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID aircraftId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID pilotPersonId,
            @Nullable UUID secondCrewPersonId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID locationId,
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
            @NotNull UUID aircraftId,
            @NotNull UUID pilotPersonId,
            @NotNull UUID locationId,
            @Nullable UUID secondCrewPersonId,
            @Nullable UUID reservationTypeId,
            @Nullable UUID flightTypeId,
            @NotNull Instant start,
            @NotNull Instant end,
            @NotNull Boolean isAllDay,
            @Nullable @Size(max = 4000) String remarks) {}

    @Schema(description = "Payload to update an aircraft reservation.")
    public record AircraftReservationUpdateRequest(
            @NotNull UUID aircraftId,
            @NotNull UUID pilotPersonId,
            @NotNull UUID locationId,
            @Nullable UUID secondCrewPersonId,
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
}
