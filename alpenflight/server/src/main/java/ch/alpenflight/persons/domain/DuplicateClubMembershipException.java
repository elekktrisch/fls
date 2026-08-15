package ch.alpenflight.persons.domain;

import java.util.UUID;

public class DuplicateClubMembershipException extends RuntimeException {

    public DuplicateClubMembershipException(UUID clubId) {
        super("Person already has an active membership in club " + clubId);
    }
}
