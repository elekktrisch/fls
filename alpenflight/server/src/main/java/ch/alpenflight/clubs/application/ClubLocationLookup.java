package ch.alpenflight.clubs.application;

import ch.alpenflight.locations.domain.LocationRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers whether a Location belongs to the club whose tenant window is
 * currently open — the ownership half of {@code Club.relocateHomebase}.
 *
 * <p>A separate bean because the {@code @Transactional} boundary has to nest
 * INSIDE the caller's {@code Tenants.runAs}: Hibernate binds a session's tenant
 * when the session opens, so a read issued on {@code ClubsService}'s already-open
 * session would filter by the PRINCIPAL's club — which for a system
 * administrator editing another club is the wrong tenant in both directions
 * (its own Locations would be accepted, the edited club's rejected). The
 * {@code JoinRequestTxWriter} precedent; self-invocation would bypass the proxy
 * and the new session with it.
 */
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
