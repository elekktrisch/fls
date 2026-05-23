package ch.alpenflight.persons.domain;

import java.util.UUID;

/**
 * Thrown when {@code joinClub} is invoked for a (person, club) pair that
 * already has an alive {@code PersonClub} row. The structural safety net is
 * the partial unique {@code ux_person_club_alive} index; the aggregate
 * raises this typed exception so the controller returns a clean 409 rather
 * than a raw {@code DataIntegrityViolationException}.
 *
 * <p>Soft-deleted memberships do NOT trigger this — re-join reactivates the
 * existing row in place (preserves history; satisfies the partial unique
 * since the prior row's {@code deleted_on} is non-null).
 */
public class DuplicateClubMembershipException extends RuntimeException {

    public DuplicateClubMembershipException(UUID clubId) {
        super("Person already has an active membership in club " + clubId);
    }
}
