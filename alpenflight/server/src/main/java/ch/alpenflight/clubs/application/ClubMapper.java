package ch.alpenflight.clubs.application;

import ch.alpenflight.clubs.application.ClubDtos.ClubResponse;
import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.platform.id.ClubStateId;
import ch.alpenflight.platform.id.CountryId;
import ch.alpenflight.platform.id.FlightTypeId;
import ch.alpenflight.platform.id.LocationId;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

final class ClubMapper {

    private ClubMapper() {}

    static ClubResponse toResponse(Club club) {
        return map(club, null);
    }

    static ClubResponse toAdminResponse(Club club) {
        return map(club, club.getJoinCode());
    }

    private static ClubResponse map(Club club, @Nullable String joinCode) {
        return new ClubResponse(
                Objects.requireNonNull(club.getId(), "Cannot map an unpersisted Club"),
                club.getClubname(),
                club.getSlug(),
                club.getClubKey(),
                club.isPublicRegistrationEnabled(),
                Objects.requireNonNull(CountryId.ofNullable(club.getCountryId()),
                        "Club is missing countryId (NOT NULL invariant in V2)"),
                Objects.requireNonNull(ClubStateId.ofNullable(club.getClubStateId()),
                        "Club is missing clubStateId (NOT NULL invariant in V2)"),
                club.getCity(),
                club.getLogoUrl(),
                club.getDiscoveryFlightOperatorEmail(),
                club.getScenicFlightOperatorEmail(),
                FlightTypeId.ofNullable(club.getDiscoveryFlightTypeId()),
                LocationId.ofNullable(club.getHomebaseId()),
                joinCode);
    }
}
