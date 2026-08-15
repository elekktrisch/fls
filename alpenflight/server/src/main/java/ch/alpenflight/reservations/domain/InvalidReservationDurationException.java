package ch.alpenflight.reservations.domain;

public class InvalidReservationDurationException extends RuntimeException {

    public InvalidReservationDurationException(String message) {
        super(message);
    }
}
