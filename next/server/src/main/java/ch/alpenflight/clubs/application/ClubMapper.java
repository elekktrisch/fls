package ch.alpenflight.clubs.application;

import ch.alpenflight.clubs.application.ClubDtos.ClubResponse;
import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.platform.id.ClubStateId;
import ch.alpenflight.platform.id.CountryId;
import java.util.Objects;

final class ClubMapper {

    private ClubMapper() {}

    static ClubResponse toResponse(Club club) {
        return new ClubResponse(
                Objects.requireNonNull(club.getId(), "Cannot map an unpersisted Club"),
                club.getClubname(),
                club.getSlug(),
                club.getClubKey(),
                club.isPublicRegistrationEnabled(),
                Objects.requireNonNull(CountryId.ofNullable(club.getCountryId()),
                        "Club is missing countryId (NOT NULL invariant in V2)"),
                Objects.requireNonNull(ClubStateId.ofNullable(club.getClubStateId()),
                        "Club is missing clubStateId (NOT NULL invariant in V2)"));
    }
}
