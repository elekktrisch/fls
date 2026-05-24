package ch.alpenflight.aircraft.application;

import ch.alpenflight.platform.id.AircraftId;
import ch.alpenflight.platform.id.AircraftStateId;
import ch.alpenflight.platform.id.AircraftTypeId;
import ch.alpenflight.platform.id.ClubId;
import ch.alpenflight.platform.id.LocationId;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * DTOs for the Aircraft REST surface. Records (immutable, explicit field
 * set); mass-assignment is structurally impossible because the controller
 * binds to the record, not to the {@link ch.alpenflight.aircraft.domain.Aircraft}
 * aggregate.
 *
 * <p>{@code ownerClubId} and {@code aircraftOwnerPersonId} are intentionally
 * <strong>absent</strong> from {@link AircraftCreateRequest} and
 * {@link AircraftUpdateRequest}: a newly-registered aircraft defaults its
 * {@code owner_club_id} to the caller's club (own-club case), and
 * ownership changes go through a dedicated transfer endpoint (A04
 * mass-assignment defense — a caller could otherwise re-key ownership
 * silently via PUT).
 *
 * <p>Do NOT re-introduce an {@code ownerClubId} field on create/update
 * without security review (A04).
 *
 * <p>{@code isFastEntryRecord} is intentionally absent from both
 * {@link AircraftCreateRequest} and {@link AircraftUpdateRequest}: it is an
 * internal marker the system sets when an aircraft is auto-drafted from the
 * flight-entry form, never a user-editable flag (legacy parity — the legacy
 * master-data form never exposed it either). The field stays on
 * {@link AircraftDetail} as a read-only signal.
 */
public final class AircraftDtos {

    private AircraftDtos() {}

    static final String IMMAT_REGEX = "^[A-Z0-9-]{2,15}$";
    static final String FLARM_REGEX = "^[A-Fa-f0-9]{6}$";
    static final String COMP_SIGN_REGEX = "^[A-Za-z0-9]{1,5}$";

    @Schema(description = "Aircraft listitem projection — sorted by immatriculation.")
    public record AircraftListItem(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AircraftId id,
            @Nullable ClubId ownerClubId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String immatriculation,
            @Nullable String competitionSign,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AircraftTypeId aircraftTypeId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String aircraftTypeCode,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasEngine,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isTowingAircraft,
            @Nullable String currentStateCode,
            @Nullable Boolean currentStateFlyable) {}

    @Schema(description = "Aircraft picker projection — slim shape for FE pickers on Flight / Reservation forms.")
    public record AircraftPickerItem(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AircraftId id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String immatriculation,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AircraftTypeId aircraftTypeId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isTowingAircraft) {}

    @Schema(description = "Single state-history entry under an Aircraft.")
    public record AircraftStateHistoryEntryResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AircraftStateId aircraftStateId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant validFrom,
            @Nullable Instant validTo,
            @Nullable UUID noticedByPersonId,
            @Nullable String remarks) {}

    @Schema(description = "Single operating-counter entry under an Aircraft.")
    public record AircraftOperatingCounterResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant atDateTime,
            @Nullable Integer totalTowedGliderStarts,
            @Nullable Integer totalWinchLaunchStarts,
            @Nullable Integer totalSelfStarts,
            @Nullable Long flightOperatingCounterInSeconds,
            @Nullable Long engineOperatingCounterInSeconds,
            @Nullable Long nextMaintenanceAtFlightOperatingCounterInSeconds,
            @Nullable Long nextMaintenanceAtEngineOperatingCounterInSeconds) {}

    @Schema(description = "Aircraft detail projection — full payload including current state + latest counter.")
    public record AircraftDetail(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AircraftId id,
            @Nullable ClubId ownerClubId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AircraftTypeId aircraftTypeId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String immatriculation,
            @Nullable String manufacturerName,
            @Nullable String aircraftModel,
            @Nullable String competitionSign,
            @Nullable String flarmId,
            @Nullable String aircraftSerialNumber,
            @Nullable LocalDate yearOfManufacture,
            @Nullable String noiseClass,
            @Nullable BigDecimal noiseLevel,
            @Nullable Integer mtom,
            @Nullable Integer nrOfSeats,
            @Nullable UUID aircraftOwnerPersonId,
            @Nullable UUID flightOperatingCounterUnitTypeId,
            @Nullable UUID engineOperatingCounterUnitTypeId,
            @Nullable LocationId homebaseId,
            @Nullable String spotLink,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isTowingOrWinchRequired,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isTowingStartAllowed,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isWinchStartAllowed,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isTowingAircraft,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isFastEntryRecord,
            @Nullable String comment,
            @Nullable Integer daecIndex,
            @Nullable AircraftStateHistoryEntryResponse currentState,
            @Nullable AircraftOperatingCounterResponse latestCounter) {}

    @Schema(description = "Payload to register a new Aircraft. Ownership defaults to the managing club; change via transfer-ownership.")
    public record AircraftCreateRequest(
            @NotNull AircraftTypeId aircraftTypeId,
            @NotBlank @Size(min = 2, max = 15)
                    @Pattern(regexp = IMMAT_REGEX, flags = Pattern.Flag.CASE_INSENSITIVE,
                            message = "immatriculation must match ^[A-Z0-9-]{2,15}$ (case-folded server-side)")
                    String immatriculation,
            @Nullable @Size(max = 100) String manufacturerName,
            @Nullable @Size(max = 50) String aircraftModel,
            @Nullable @Pattern(regexp = COMP_SIGN_REGEX) String competitionSign,
            @Nullable @Pattern(regexp = FLARM_REGEX) String flarmId,
            @Nullable @Size(max = 20) String aircraftSerialNumber,
            @Nullable LocalDate yearOfManufacture,
            @Nullable @Size(max = 1) String noiseClass,
            @Nullable BigDecimal noiseLevel,
            @Nullable @Min(0) Integer mtom,
            @Nullable @Min(0) Integer nrOfSeats,
            @Nullable UUID flightOperatingCounterUnitTypeId,
            @Nullable UUID engineOperatingCounterUnitTypeId,
            @Nullable LocationId homebaseId,
            @Nullable @Size(max = 250)
                    @Pattern(regexp = "^https://.*", flags = Pattern.Flag.CASE_INSENSITIVE,
                            message = "spotLink must start with https:// (A10 SSRF defense-in-depth)")
                    String spotLink,
            boolean isTowingOrWinchRequired,
            boolean isTowingStartAllowed,
            boolean isWinchStartAllowed,
            boolean isTowingAircraft,
            @Nullable @Size(max = 250) String comment,
            @Nullable Integer daecIndex) {}

    @Schema(description = "Payload to update an Aircraft. Ownership fields are not settable here — use the transfer-ownership endpoint (A04 defense).")
    public record AircraftUpdateRequest(
            @NotNull AircraftTypeId aircraftTypeId,
            @NotBlank @Size(min = 2, max = 15)
                    @Pattern(regexp = IMMAT_REGEX, flags = Pattern.Flag.CASE_INSENSITIVE,
                            message = "immatriculation must match ^[A-Z0-9-]{2,15}$ (case-folded server-side)")
                    String immatriculation,
            @Nullable @Size(max = 100) String manufacturerName,
            @Nullable @Size(max = 50) String aircraftModel,
            @Nullable @Pattern(regexp = COMP_SIGN_REGEX) String competitionSign,
            @Nullable @Pattern(regexp = FLARM_REGEX) String flarmId,
            @Nullable @Size(max = 20) String aircraftSerialNumber,
            @Nullable LocalDate yearOfManufacture,
            @Nullable @Size(max = 1) String noiseClass,
            @Nullable BigDecimal noiseLevel,
            @Nullable @Min(0) Integer mtom,
            @Nullable @Min(0) Integer nrOfSeats,
            @Nullable UUID flightOperatingCounterUnitTypeId,
            @Nullable UUID engineOperatingCounterUnitTypeId,
            @Nullable LocationId homebaseId,
            @Nullable @Size(max = 250)
                    @Pattern(regexp = "^https://.*", flags = Pattern.Flag.CASE_INSENSITIVE,
                            message = "spotLink must start with https:// (A10 SSRF defense-in-depth)")
                    String spotLink,
            boolean isTowingOrWinchRequired,
            boolean isTowingStartAllowed,
            boolean isWinchStartAllowed,
            boolean isTowingAircraft,
            @Nullable @Size(max = 250) String comment,
            @Nullable Integer daecIndex) {}

    @Schema(description = "Payload to record a new state for an Aircraft (closes the previous open period).")
    public record AircraftStateChangeRequest(
            @NotNull AircraftStateId aircraftStateId,
            @NotNull Instant validFrom,
            @Nullable UUID noticedByPersonId,
            @Nullable @Size(max = 500) String remarks) {}

    @Schema(description = "Payload to record a new operating-counter snapshot.")
    public record AircraftCounterRecordRequest(
            @NotNull Instant atDateTime,
            @Nullable @Min(0) Integer totalTowedGliderStarts,
            @Nullable @Min(0) Integer totalWinchLaunchStarts,
            @Nullable @Min(0) Integer totalSelfStarts,
            @Nullable @Min(0) Long flightOperatingCounterInSeconds,
            @Nullable @Min(0) Long engineOperatingCounterInSeconds,
            @Nullable @Min(0) Long nextMaintenanceAtFlightOperatingCounterInSeconds,
            @Nullable @Min(0) Long nextMaintenanceAtEngineOperatingCounterInSeconds) {}

    @Schema(description = "CLUB_ADMINISTRATOR-only payload to transfer ownership metadata. Managing tenant is unchanged.")
    public record AircraftTransferOwnershipRequest(
            @Nullable ClubId newOwnerClubId,
            @Nullable UUID newOwnerPersonId) {}

    @Schema(description = "Paginated list of state-history entries (newest first).")
    public record AircraftStateHistory(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
                    List<AircraftStateHistoryEntryResponse> entries) {}

    @Schema(description = "Paginated list of operating-counter entries (newest first).")
    public record AircraftCounterHistory(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
                    List<AircraftOperatingCounterResponse> entries) {}
}
