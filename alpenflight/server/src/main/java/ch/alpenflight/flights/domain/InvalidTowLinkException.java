package ch.alpenflight.flights.domain;

public class InvalidTowLinkException extends RuntimeException {

    public InvalidTowLinkException(String message) {
        super(message);
    }
}
