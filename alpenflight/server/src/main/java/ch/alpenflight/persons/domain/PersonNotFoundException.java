package ch.alpenflight.persons.domain;

import ch.alpenflight.platform.id.PersonId;

/**
 * Thrown when a Person lookup yields no row visible to the caller's tenant
 * scope. Mapped to HTTP 404 by the web-layer advice — never 403, to avoid
 * leaking existence of cross-tenant rows to a CLUB_ADMINISTRATOR.
 */
public class PersonNotFoundException extends RuntimeException {

    public PersonNotFoundException(PersonId id) {
        super("Person " + id + " not found");
    }

    public PersonNotFoundException(String message) {
        super(message);
    }
}
