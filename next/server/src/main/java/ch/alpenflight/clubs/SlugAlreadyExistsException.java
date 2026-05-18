package ch.alpenflight.clubs;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Service-layer signal that a club slug collides with an existing row.
 * Mapped to HTTP 409 via {@link ResponseStatus}.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class SlugAlreadyExistsException extends RuntimeException {

    public SlugAlreadyExistsException(String slug) {
        super("Club slug already in use: " + slug);
    }
}
