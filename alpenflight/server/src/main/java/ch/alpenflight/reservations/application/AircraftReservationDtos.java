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

    @Schema(description = "Candidate aircraft-slot fields to pre-check for overlap (non-mutating).")
    public record AircraftReservationValidateRequest(
            @NotNull AircraftId aircraftId,
            @NotNull Instant start,
            @NotNull Instant end,
            @NotNull Boolean isAllDay,
            @Nullable @Schema(description = "On an edit, the reservation's own id — excluded from the "
                    + "overlap probe so it does not self-conflict. Absent on a create check.")
                    UUID excludeReservationId) {}

    @Schema(description = "Field-level validation outcome for an inline pre-check (200; valid flag + offending field).")
    public record ReservationValidationResult(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean valid,
            @Nullable String field,
            @Nullable String message) {

        public static ReservationValidationResult passed() {
            return new ReservationValidationResult(true, null, null);
        }

        public static ReservationValidationResult failed(String field, String message) {
            return new ReservationValidationResult(false, field, message);
        }
    }

    @Schema(description = "Reservation-type listitem for the type dropdown.")
    public record AircraftReservationTypeListItem(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean active,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                            description = "Whether picking this type requires a Second-Crew person."
                                    + " AlpenFlight's reservation-type model carries only the single"
                                    + " is_instructor_required flag; the legacy"
                                    + " ObserverPilotOrInstructorRequired / IsPassengerRequired"
                                    + " FlightType-derived flags are NOT modeled here, so this one"
                                    + " boolean is the collapsed second-crew-required driver for the"
                                    + " type lane (the aircraft NrOfSeats>1 driver rides the picker).")
                    boolean instructorRequired) {}

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

    @Schema(description = "Paged aircraft-reservation list envelope (SPA-compat page shape).")
    public record AircraftReservationPage(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<AircraftReservationListItem> items,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int pageStart,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int pageSize,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long totalRows) {}

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
