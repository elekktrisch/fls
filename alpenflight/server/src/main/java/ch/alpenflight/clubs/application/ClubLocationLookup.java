package ch.alpenflight.clubs.application;

import ch.alpenflight.locations.domain.LocationRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class ClubLocationLookup {

    private final LocationRepository locations;

    ClubLocationLookup(LocationRepository locations) {
        this.locations = locations;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean isActiveLocationOfCurrentTenant(UUID locationId) {
        return locations.findActiveById(locationId).isPresent();
    }
}
