package ch.alpenflight.clubs.application;

import ch.alpenflight.clubs.application.ClubDtos.ClubResponse;
import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.platform.id.ClubStateId;
import ch.alpenflight.platform.id.CountryId;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

final class ClubMapper {

    private ClubMapper() {}

    /**
     * Default projection: {@code joinCode} is {@code null}. Used for list /
     * mutation / audit-snapshot paths and for non-admin readers — the code is
     * admin-only on the wire and a quasi-secret in the audit ledger (S-177).
     */
    static ClubResponse toResponse(Club club) {
        return map(club, null);
    }

    /** Admin projection: includes the club's current {@code joinCode}. */
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
                joinCode);
    }
}
