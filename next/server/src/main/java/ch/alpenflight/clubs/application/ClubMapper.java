package ch.alpenflight.clubs.application;

import ch.alpenflight.clubs.application.ClubDtos.ClubResponse;
import ch.alpenflight.clubs.domain.Club;
import java.util.Objects;

final class ClubMapper {

    private ClubMapper() {}

    static ClubResponse toResponse(Club club) {
        return new ClubResponse(
                Objects.requireNonNull(club.getId(), "Cannot map an unpersisted Club"),
                club.getClubname(),
                club.getSlug(),
                club.getClubKey(),
                club.isPublicRegistrationEnabled());
    }
}
