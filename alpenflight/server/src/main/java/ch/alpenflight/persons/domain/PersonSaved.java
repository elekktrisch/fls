package ch.alpenflight.persons.domain;

import java.util.UUID;

/**
 * Domain event published by Spring Data on every {@code PersonRepository}
 * save via {@link Person}'s {@code @DomainEvents} method (the Flight /
 * Deployment precedent, J-7 RM-2). The flight-report read-model listens
 * synchronously (same transaction — ADR 0027 §2) and re-projects the rows
 * whose denormalized {@code pilot_name} / {@code second_crew_name} /
 * {@code tow_pilot_name} strings derive from this person.
 *
 * <p>Carries only the id: the listener finds the affected flights via the
 * read-model's own crew child ({@code t_flight_report_crew.person_id}) and
 * re-projects whole rows — names are never patched in place because the same
 * person can sit on a row as pilot, second crew, or tow pilot.
 */
public record PersonSaved(UUID personId) {

    public PersonSaved {
        if (personId == null) {
            throw new IllegalArgumentException("personId must not be null");
        }
    }
}
