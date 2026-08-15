package ch.alpenflight.clubs.domain;

import ch.alpenflight.platform.id.ClubId;

public class ClubNotFoundException extends RuntimeException {

    public ClubNotFoundException(ClubId id) {
        super("Club not found: " + id);
    }
}
