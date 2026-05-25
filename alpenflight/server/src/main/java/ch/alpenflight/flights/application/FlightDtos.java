package ch.alpenflight.flights.application;

import ch.alpenflight.flights.domain.FlightAircraftType;
import ch.alpenflight.flights.domain.FlightAirState;
import ch.alpenflight.flights.domain.FlightProcessState;
import ch.alpenflight.platform.id.AircraftId;
import ch.alpenflight.platform.id.FlightCostBalanceTypeId;
import ch.alpenflight.platform.id.FlightId;
import ch.alpenflight.platform.id.FlightTypeId;
import ch.alpenflight.platform.id.LocationId;
import ch.alpenflight.platform.id.PersonId;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * DTOs for the Flight REST surface. Records (immutable, explicit field
 * set); mass-assignment is structurally impossible because the controller
 * binds to the record, not to the {@link ch.alpenflight.flights.domain.Flight}
 * aggregate.
 *
 * <p>{@code FlightCreateRequest} / {@code FlightUpdateRequest} explicitly
 * exclude the state-machine columns ({@code processStateId},
 * {@code validatedOn}, {@code deliveryCreatedOn}, {@code flightReportSentOn},
 * {@code validationErrors}) and tenant/audit metadata. Air-state is computed
 * (S-060) and response-only — {@code FlightDetail.airState} carries the
 * enum-name string; it never appears on a create/update request.
 * The discriminator {@code flightAircraftType} is required on create and
 * immutable post-create (absent from {@code FlightUpdateRequest}).
 */
public final class FlightDtos {

    private FlightDtos() {}

    @Schema(description = "Flight list-row projection — basic CRUD scope; decorations (aircraft immat, pilot name) deferred. Carries `version` so concurrency-aware actions (e.g. DELETE with If-Match) can fire from the list view without a follow-up GET.")
    public record FlightListItem(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) FlightId id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) FlightAircraftType flightAircraftType,
            @Nullable LocalDate flightDate,
            @Nullable Instant startDateTime,
            @Nullable Instant ldgDateTime,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AircraftId aircraftId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID processStateId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) FlightProcessState processState,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) FlightAirState airState,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long version) {}

    @Schema(description = "Keyset-cursor list response. Filter via from/to; scroll via nextCursor.")
    public record FlightListResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<FlightListItem> items,
            @Nullable String nextCursor) {}

    @Schema(description = "Single crew row under a Flight.")
    public record FlightCrewItem(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) PersonId personId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID flightCrewTypeId,
            @Nullable Instant beginFlightDatetime,
            @Nullable Instant endFlightDatetime,
            @Nullable Instant beginInstructionDatetime,
            @Nullable Instant endInstructionDatetime,
            @Nullable Short nrOfLdgs,
            @Nullable Short nrOfStarts) {}

    @Schema(description = "Flight detail projection — full payload + crew.")
    public record FlightDetail(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) FlightId id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) FlightAircraftType flightAircraftType,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AircraftId aircraftId,
            @Nullable LocalDate flightDate,
            @Nullable Instant startDateTime,
            @Nullable Instant ldgDateTime,
            @Nullable Instant blockStartDateTime,
            @Nullable Instant blockEndDateTime,
            @Nullable LocationId startLocationId,
            @Nullable LocationId ldgLocationId,
            @Nullable String startRunway,
            @Nullable String ldgRunway,
            @Nullable String outboundRoute,
            @Nullable String inboundRoute,
            @Nullable FlightTypeId flightTypeId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isSoloFlight,
            @Nullable UUID startTypeId,
            @Nullable FlightId towFlightId,
            @Nullable Short nrOfLdgs,
            @Nullable Short nrOfLdgsOnStartLocation,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean noStartTimeInformation,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean noLdgTimeInformation,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) FlightAirState airState,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID processStateId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long version,
            @Nullable Long engineStartOperatingCounterInSeconds,
            @Nullable Long engineEndOperatingCounterInSeconds,
            @Nullable String comment,
            @Nullable String incidentComment,
            @Nullable String couponNumber,
            @Nullable FlightCostBalanceTypeId flightCostBalanceTypeId,
            @Nullable Short nrOfPassengers,
            @Nullable Short startPosition,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<FlightCrewItem> crew) {}

    @Schema(description = "Editable-surface projection for new-flight and copy-flight templates. Carries no id / version / state-machine / audit metadata. Times + comments + engine counters are cleared on copy-template per legacy `FlightsController.js:232-255`.")
    public record FlightTemplateResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) FlightAircraftType flightAircraftType,
            @Nullable AircraftId aircraftId,
            @Nullable LocalDate flightDate,
            @Nullable LocationId startLocationId,
            @Nullable LocationId ldgLocationId,
            @Nullable String startRunway,
            @Nullable String ldgRunway,
            @Nullable String outboundRoute,
            @Nullable String inboundRoute,
            @Nullable FlightTypeId flightTypeId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isSoloFlight,
            @Nullable UUID startTypeId,
            @Nullable FlightId towFlightId,
            @Nullable Short nrOfLdgs,
            @Nullable Short nrOfLdgsOnStartLocation,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean noStartTimeInformation,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean noLdgTimeInformation,
            @Nullable FlightCostBalanceTypeId flightCostBalanceTypeId,
            @Nullable Short nrOfPassengers,
            @Nullable Short startPosition,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<FlightCrewItem> crew) {}

    @Schema(description = "Last-flight context for the (aircraft, date) tuple — seeds the flight-edit form's empty state per the mobile-first amendment (vision §F6 / AC-DIR-1). 404 when no prior flight matches.")
    public record FlightLastContextResponse(
            @Nullable FlightTypeId flightTypeId,
            @Nullable PersonId pilotPersonId,
            @Nullable PersonId invoiceRecipientPersonId,
            @Nullable LocationId startLocationId,
            @Nullable LocationId ldgLocationId,
            @Nullable String outboundRoute,
            @Nullable String inboundRoute,
            @Nullable UUID startTypeId,
            @Nullable FlightCostBalanceTypeId flightCostBalanceTypeId,
            FlightLastContextResponse.@Nullable TowContext tow) {

        /** Tow-specific context populated only when the last flight was towed. */
        public record TowContext(
                @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AircraftId aircraftId,
                @Nullable PersonId pilotPersonId,
                @Nullable FlightTypeId flightTypeId,
                @Nullable LocationId ldgLocationId) {}
    }

    @Schema(description = "Request body for `PATCH /api/v1/flights/{id}/process-state`. The transition matrix gates which state changes are legal under the OPERATOR trigger.")
    public record FlightProcessStateChangeRequest(
            @NotNull
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            ch.alpenflight.flights.domain.FlightProcessState processState) {}

    @Schema(description = "Response for a successful PATCH on process-state — minimal payload reflecting the persisted change.")
    public record FlightProcessStateResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) FlightId id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            ch.alpenflight.flights.domain.FlightProcessState processState,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID processStateId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long version) {}

    @Schema(description = "Payload to create a Flight. State-machine columns are server-set; ownership tenant rides from the JWT (A04 defense).")
    public record FlightCreateRequest(
            @NotNull FlightAircraftType flightAircraftType,
            @NotNull AircraftId aircraftId,
            @Nullable LocalDate flightDate,
            @Nullable Instant startDateTime,
            @Nullable Instant ldgDateTime,
            @Nullable Instant blockStartDateTime,
            @Nullable Instant blockEndDateTime,
            @Nullable LocationId startLocationId,
            @Nullable LocationId ldgLocationId,
            @Nullable @Size(max = 5) String startRunway,
            @Nullable @Size(max = 5) String ldgRunway,
            @Nullable @Size(max = 50) String outboundRoute,
            @Nullable @Size(max = 50) String inboundRoute,
            @Nullable FlightTypeId flightTypeId,
            @Nullable UUID startTypeId,
            @Nullable @Min(0) Short nrOfLdgs,
            @Nullable @Min(0) Short nrOfLdgsOnStartLocation,
            boolean noStartTimeInformation,
            boolean noLdgTimeInformation,
            @Nullable @Min(0) Long engineStartOperatingCounterInSeconds,
            @Nullable @Min(0) Long engineEndOperatingCounterInSeconds,
            @Nullable String comment,
            @Nullable String incidentComment,
            @Nullable @Size(max = 20) String couponNumber,
            @Nullable FlightCostBalanceTypeId flightCostBalanceTypeId,
            @Nullable @Min(0) Short nrOfPassengers,
            @Nullable Short startPosition,
            boolean isSoloFlight,
            @Nullable @Valid List<FlightCrewItem> crew) {}

    @Schema(description = "Payload to update a Flight. The discriminator (flightAircraftType) is immutable and absent here; aircraftId is mutable at S-058 scope (state-machine gating against booked/invoiced flights is S-059).")
    public record FlightUpdateRequest(
            @NotNull AircraftId aircraftId,
            @Nullable LocalDate flightDate,
            @Nullable Instant startDateTime,
            @Nullable Instant ldgDateTime,
            @Nullable Instant blockStartDateTime,
            @Nullable Instant blockEndDateTime,
            @Nullable LocationId startLocationId,
            @Nullable LocationId ldgLocationId,
            @Nullable @Size(max = 5) String startRunway,
            @Nullable @Size(max = 5) String ldgRunway,
            @Nullable @Size(max = 50) String outboundRoute,
            @Nullable @Size(max = 50) String inboundRoute,
            @Nullable FlightTypeId flightTypeId,
            @Nullable UUID startTypeId,
            @Nullable @Min(0) Short nrOfLdgs,
            @Nullable @Min(0) Short nrOfLdgsOnStartLocation,
            boolean noStartTimeInformation,
            boolean noLdgTimeInformation,
            @Nullable @Min(0) Long engineStartOperatingCounterInSeconds,
            @Nullable @Min(0) Long engineEndOperatingCounterInSeconds,
            @Nullable String comment,
            @Nullable String incidentComment,
            @Nullable @Size(max = 20) String couponNumber,
            @Nullable FlightCostBalanceTypeId flightCostBalanceTypeId,
            @Nullable @Min(0) Short nrOfPassengers,
            @Nullable Short startPosition,
            boolean isSoloFlight,
            @Nullable FlightId towFlightId,
            @Nullable @Valid List<FlightCrewItem> crew) {}
}
