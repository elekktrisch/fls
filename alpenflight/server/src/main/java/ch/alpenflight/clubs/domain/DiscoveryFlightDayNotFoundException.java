package ch.alpenflight.clubs.domain;

import java.util.UUID;

public class DiscoveryFlightDayNotFoundException extends RuntimeException {

    public DiscoveryFlightDayNotFoundException(UUID id) {
        super("DiscoveryFlightDay not found: " + id);
    }
}
