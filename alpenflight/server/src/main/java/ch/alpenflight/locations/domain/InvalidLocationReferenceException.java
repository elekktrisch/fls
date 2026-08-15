package ch.alpenflight.locations.domain;

public class InvalidLocationReferenceException extends RuntimeException {

    private final String field;

    public InvalidLocationReferenceException(String field) {
        super("Unknown " + field);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
