package ch.alpenflight.persons.domain;

import java.util.UUID;

public record PersonSaved(UUID personId) {

    public PersonSaved {
        if (personId == null) {
            throw new IllegalArgumentException("personId must not be null");
        }
    }
}
