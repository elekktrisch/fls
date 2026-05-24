package ch.alpenflight.flights.domain;

/**
 * Thrown when a Flight's crew list contains two rows with the same
 * {@code (person_id, flight_crew_type_id)} pair — the partial-unique
 * {@code ux_flight_crew_unique} catches it at the DB if a service-layer
 * pre-check misses; the aggregate enforces the same rule on
 * {@link Flight#replaceCrew} for friendlier 400 responses.
 */
public class DuplicateCrewMemberException extends RuntimeException {

    public DuplicateCrewMemberException(String message) {
        super(message);
    }
}
