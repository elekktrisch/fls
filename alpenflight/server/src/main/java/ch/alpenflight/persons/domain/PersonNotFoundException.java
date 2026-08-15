package ch.alpenflight.persons.domain;

import ch.alpenflight.platform.id.PersonId;

public class PersonNotFoundException extends RuntimeException {

    public PersonNotFoundException(PersonId id) {
        super("Person " + id + " not found");
    }

    public PersonNotFoundException(String message) {
        super(message);
    }
}
