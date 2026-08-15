package ch.alpenflight.clubs.domain;

import java.time.LocalDate;

public class DiscoveryFlightDayAlreadyScheduledException extends RuntimeException {

    public DiscoveryFlightDayAlreadyScheduledException(LocalDate eventDate) {
        super("The club already offers a discovery-flight day on " + eventDate);
    }
}
