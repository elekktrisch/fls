package ch.alpenflight.locations.domain;

import java.util.UUID;

public record LocationSaved(UUID locationId) {

    public LocationSaved {
        if (locationId == null) {
            throw new IllegalArgumentException("locationId must not be null");
        }
    }
}
