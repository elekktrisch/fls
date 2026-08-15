package ch.alpenflight.clubs.domain;

public class InvalidClubReferenceException extends RuntimeException {

    private final String field;

    public InvalidClubReferenceException(String field) {
        super("Unknown " + field);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
