package ch.alpenflight.publicregistration.application;

import ch.alpenflight.platform.id.PersonId;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/**
 * Wire shapes for the anonymous registration surface.
 *
 * <p>The registrant block is {@link PublicRegistrantDetails} itself rather than
 * a parallel request record: its compact constructor IS the field contract
 * (address / zip / city, one reachable contact channel, the invoice block), so a
 * second record would either duplicate the rules or let a caller past them. Both
 * public flows post the same registrant — the scenic form is this one minus the
 * day selection — so the nesting is what lets the two requests share it verbatim.
 *
 * <p>The records here validate only that the two body members are present.
 * Anything a caller can get wrong about a field is the command's business, and
 * both surface as the same 400 ({@link PublicRegistrationInvalidException}).
 */
public final class PublicRegistrationDtos {

    private PublicRegistrationDtos() {}

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

    /**
     * What the success panel gets back. Deliberately not the reservation
     * outcome: whether the club's double-seater could be blocked is organiser
     * information (it rides the organiser mail), and a candidate who reads
     * "no reservation" would take it for a rejection.
     */
    @Schema(description = "The accepted discovery-flight registration.")
    public record DiscoveryFlightRegistrationResponse(
            PersonId registrantPersonId,
            String clubName,
            LocalDate selectedDay) {}
}
