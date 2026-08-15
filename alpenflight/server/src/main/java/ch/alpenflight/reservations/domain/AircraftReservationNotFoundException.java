package ch.alpenflight.reservations.domain;

import java.util.UUID;

public class AircraftReservationNotFoundException extends RuntimeException {

    public AircraftReservationNotFoundException(UUID id) {
        super("Aircraft reservation not found: " + id);
    }
}
