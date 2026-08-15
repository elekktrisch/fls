package ch.alpenflight.aircraft.domain;

public class InvalidAircraftReferenceException extends RuntimeException {

    private final String field;

    public InvalidAircraftReferenceException(String field) {
        super("Unknown " + field);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
