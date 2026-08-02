package ch.alpenflight.clubs.domain;

import java.time.LocalDate;

/**
 * Thrown when the club already offers a live discovery-flight day on the
 * requested date. Uniqueness is per-tenant and ignores withdrawn rows — the
 * V58 partial UNIQUE {@code ux_discovery_flight_day_club_date} on
 * {@code (club_id, event_date) WHERE deleted_on IS NULL} is the structural race
 * catcher, so re-publishing a withdrawn date is not a conflict.
 */
public class DiscoveryFlightDayAlreadyScheduledException extends RuntimeException {

    public DiscoveryFlightDayAlreadyScheduledException(LocalDate eventDate) {
        super("The club already offers a discovery-flight day on " + eventDate);
    }
}
