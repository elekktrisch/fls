package ch.alpenflight.publicregistration.application;

import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.publicregistration.application.PublicClubUnavailableException.Reason;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PublicClubResolver {

    private final ClubRepository clubs;

    public PublicClubResolver(ClubRepository clubs) {
        this.clubs = clubs;
    }

    public PublicClub resolve(String clubSlug) {
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

    public record PublicClub(UUID clubId, String clubName) {}
}
