package ch.alpenflight.clubs.application;

import ch.alpenflight.platform.id.ClubId;
import ch.alpenflight.platform.id.ClubStateId;
import ch.alpenflight.platform.id.CountryId;
import ch.alpenflight.platform.id.FlightTypeId;
import ch.alpenflight.platform.id.LocationId;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * DTOs for the Clubs REST surface. Records (immutable, explicit field set);
 * mass-assignment is structurally impossible because the controller binds to
 * the record, not to {@link Club}.
 *
 * <p>Bean Validation here is fast-fail at the HTTP boundary; the aggregate
 * re-validates per ADR 0022 directive 2.
 */
public final class ClubDtos {

    private ClubDtos() {}

    private static final String OPERATOR_EMAIL_DESCRIPTION =
            "Organiser-notification recipients, separated by comma, semicolon or whitespace. "
                    + "Null or blank means the club receives no organiser mail, which is a valid "
                    + "state — the registration still succeeds.";

    private static final String DISCOVERY_FLIGHT_TYPE_DESCRIPTION =
            "Flight type stamped on the reservation a discovery-flight registration books; "
                    + "null books the reservation without one.";

    private static final String HOMEBASE_DESCRIPTION =
            "The club's home airfield — must be one of the club's OWN Locations. "
                    + "Null clears it, which is a valid state: a club without a homebase still "
                    + "accepts discovery-flight registrations, only the reservation is skipped. "
                    + "The update is full-replace, so an omitted key clears the homebase too.";

    @Schema(description = "Club projection returned to API consumers.")
    public record ClubResponse(
            ClubId id,
            String name,
            @Nullable String slug,
            String clubKey,
            boolean publicRegistrationEnabled,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) CountryId countryId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ClubStateId clubStateId,
            @Schema(description = "Public-display city, or null.")
            @Nullable String city,
            @Schema(description = "Public-display logo URL, or null.")
            @Nullable String logoUrl,
            @Schema(description = OPERATOR_EMAIL_DESCRIPTION + " Discovery-flight registrations.")
            @Nullable String discoveryFlightOperatorEmail,
            @Schema(description = OPERATOR_EMAIL_DESCRIPTION + " Scenic-flight registrations.")
            @Nullable String scenicFlightOperatorEmail,
            @Schema(description = DISCOVERY_FLIGHT_TYPE_DESCRIPTION)
            @Nullable FlightTypeId discoveryFlightTypeId,
            @Schema(description = HOMEBASE_DESCRIPTION)
            @Nullable LocationId homebaseId,
            @Schema(description = "Club join code — present only for a CLUB_ADMINISTRATOR; null otherwise.")
            @Nullable String joinCode) {}

    @Schema(description = "The newly rotated club join code.")
    public record JoinCodeResponse(String joinCode) {}

    @Schema(description = "Payload to create a new club.")
    public record ClubCreateRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(min = 3, max = 64) @Pattern(regexp = "^[a-z0-9-]+$") String slug,
            @NotBlank @Size(max = 10) String clubKey,
            boolean publicRegistrationEnabled,
            @NotNull CountryId countryId,
            @NotNull ClubStateId clubStateId) {}

    @Schema(description = "Payload to update a club. `clubKey` is immutable post-create.")
    public record ClubUpdateRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(min = 3, max = 64) @Pattern(regexp = "^[a-z0-9-]+$") String slug,
            boolean publicRegistrationEnabled,
            @NotNull CountryId countryId,
            @NotNull ClubStateId clubStateId,
            @Schema(description = OPERATOR_EMAIL_DESCRIPTION + " Discovery-flight registrations.")
            @Size(max = 250) @Nullable String discoveryFlightOperatorEmail,
            @Schema(description = OPERATOR_EMAIL_DESCRIPTION + " Scenic-flight registrations.")
            @Size(max = 250) @Nullable String scenicFlightOperatorEmail,
            @Schema(description = DISCOVERY_FLIGHT_TYPE_DESCRIPTION)
            @Nullable FlightTypeId discoveryFlightTypeId,
            @Schema(description = HOMEBASE_DESCRIPTION)
            @Nullable LocationId homebaseId) {}
}
