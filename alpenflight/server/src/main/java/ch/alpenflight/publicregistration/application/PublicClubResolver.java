package ch.alpenflight.publicregistration.application;

import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.publicregistration.application.PublicClubUnavailableException.Reason;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Turns the {@code {clubSlug}} path segment of an anonymous public-registration
 * URL into the target tenant (S-025).
 *
 * <p>Runs with NO tenant scope: {@code Club} is the tenant root rather than a
 * {@code @TenantId}-scoped entity, so the lookup needs none, and the rejection
 * paths must not have one — an unresolved or closed club may not leave a
 * tenant-scoped side effect of any kind. Opening the window is a separate,
 * deliberate step ({@link PublicRegistrationIntake}) that only the accepted
 * submission reaches.
 */
@Service
public class PublicClubResolver {

    private final ClubRepository clubs;

    public PublicClubResolver(ClubRepository clubs) {
        this.clubs = clubs;
    }

    /**
     * Resolves an accepted public-registration target from its URL slug.
     *
     * @throws PublicClubUnavailableException 404 for an unknown / malformed slug,
     *         403 for a club that has not opted into public registration
     */
    public PublicClub resolve(String clubSlug) {
        // Reject a malformed segment without querying: an anonymous caller must
        // not be able to turn arbitrary URL text into a database round-trip.
        if (!Club.isWellFormedSlug(clubSlug)) {
            throw new PublicClubUnavailableException(Reason.UNKNOWN);
        }
        Club club = clubs.findActiveBySlug(clubSlug)
                .orElseThrow(() -> new PublicClubUnavailableException(Reason.UNKNOWN));
        if (!club.acceptsPublicRegistration()) {
            throw new PublicClubUnavailableException(Reason.REGISTRATION_CLOSED);
        }
        UUID id = requireId(club);
        return new PublicClub(id, club.getClubname());
    }

    private static UUID requireId(Club club) {
        if (club.getId() == null) {
            throw new IllegalStateException("Persisted club has no id: " + club.getSlug());
        }
        return club.getId().value();
    }

    /**
     * The resolved tenant plus the one club attribute the public forms display.
     * Deliberately not the {@code Club} aggregate — everything downstream of
     * resolution is anonymous-facing.
     */
    public record PublicClub(UUID clubId, String clubName) {}
}
