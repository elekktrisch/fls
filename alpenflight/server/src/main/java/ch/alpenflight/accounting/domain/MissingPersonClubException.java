package ch.alpenflight.accounting.domain;

public class MissingPersonClubException extends RuntimeException {

    public MissingPersonClubException(String message) {
        super(message);
    }
}
