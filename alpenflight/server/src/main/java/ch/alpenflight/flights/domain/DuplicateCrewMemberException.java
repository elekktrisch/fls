package ch.alpenflight.flights.domain;

public class DuplicateCrewMemberException extends RuntimeException {

    public DuplicateCrewMemberException(String message) {
        super(message);
    }
}
