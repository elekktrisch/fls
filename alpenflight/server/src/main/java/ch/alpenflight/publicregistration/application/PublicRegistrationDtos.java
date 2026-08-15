package ch.alpenflight.publicregistration.application;

import ch.alpenflight.platform.id.PersonId;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

public final class PublicRegistrationDtos {

    private PublicRegistrationDtos() {}

    @Schema(description = "The public identity of a club that accepts anonymous registration.")
    public record PublicClubResponse(String clubName) {}

    @Schema(description = "A discovery-flight submission: who registers, and for which "
            + "published day.")
    public record DiscoveryFlightRegistrationRequest(
            PublicRegistrantDetails registrant,
            LocalDate selectedDay) {

        public DiscoveryFlightRegistrationRequest {
            if (registrant == null) {
                throw new PublicRegistrationInvalidException("registrant is required");
            }
            if (selectedDay == null) {
                throw new PublicRegistrationInvalidException("selectedDay is required");
            }
        }
    }

    @Schema(description = "The accepted discovery-flight registration.")
    public record DiscoveryFlightRegistrationResponse(
            PersonId registrantPersonId,
            String clubName,
            LocalDate selectedDay) {}

    @Schema(description = "A scenic-flight submission: who registers.")
    public record ScenicFlightRegistrationRequest(PublicRegistrantDetails registrant) {

        public ScenicFlightRegistrationRequest {
            if (registrant == null) {
                throw new PublicRegistrationInvalidException("registrant is required");
            }
        }
    }

    @Schema(description = "The accepted scenic-flight registration.")
    public record ScenicFlightRegistrationResponse(
            PersonId registrantPersonId,
            String clubName) {}
}
