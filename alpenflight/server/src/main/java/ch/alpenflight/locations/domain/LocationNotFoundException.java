package ch.alpenflight.locations.domain;

import ch.alpenflight.platform.id.LocationId;

public class LocationNotFoundException extends RuntimeException {

    public LocationNotFoundException(LocationId id) {
        super("Location not found: " + id);
    }
}
