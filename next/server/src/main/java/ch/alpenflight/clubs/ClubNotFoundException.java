package ch.alpenflight.clubs;

import ch.alpenflight.platform.id.ClubId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a Clubs endpoint is asked to read / mutate a non-existent or
 * soft-deleted club. Mapped to HTTP 404.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class ClubNotFoundException extends RuntimeException {

    public ClubNotFoundException(ClubId id) {
        super("Club not found: " + id);
    }
}
