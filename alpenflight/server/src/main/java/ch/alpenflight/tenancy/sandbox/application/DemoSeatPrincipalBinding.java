package ch.alpenflight.tenancy.sandbox.application;

import ch.alpenflight.platform.security.PrincipalClubBindingRule;
import ch.alpenflight.tenancy.sandbox.domain.DemoSeat;
import ch.alpenflight.tenancy.sandbox.domain.DemoSeatRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
public class DemoSeatPrincipalBinding implements PrincipalClubBindingRule {

    private final DemoSeatRepository seats;

    private volatile @Nullable Map<UUID, String>
            seatUsernameByClubIdCachedOnlyAfterAReadThatFoundASeat;

    public DemoSeatPrincipalBinding(DemoSeatRepository seats) {
        this.seats = seats;
    }

    @Override
    public boolean refusesPrincipalCarryingClub(String preferredUsername, UUID clubId) {
        Map<UUID, String> pool = pool();
        String usernameOfTheSeatThatOwnsTheCarriedClub = pool.get(clubId);
        boolean theCarriedClubIsASandboxSeat = usernameOfTheSeatThatOwnsTheCarriedClub != null;
        boolean thePrincipalOwnsSomeSeat = pool.containsValue(preferredUsername);
        if (!theCarriedClubIsASandboxSeat && !thePrincipalOwnsSomeSeat) {
            return false;
        }
        return !preferredUsername.equals(usernameOfTheSeatThatOwnsTheCarriedClub);
    }

    private Map<UUID, String> loadPool() {
        Map<UUID, String> byClubId = new LinkedHashMap<>();
        for (DemoSeat seat : seats.findAllInSeatNumberOrder()) {
            byClubId.put(seat.getClubId().value(), seat.getKeycloakUsername());
        }
        return Map.copyOf(byClubId);
    }

    private Map<UUID, String> pool() {
        Map<UUID, String> known = this.seatUsernameByClubIdCachedOnlyAfterAReadThatFoundASeat;
        if (known != null) {
            return known;
        }
        Map<UUID, String> loaded = loadPool();
        boolean theReadFoundNoSeatSoTheNextRequestMustReadThePoolAgain = loaded.isEmpty();
        if (theReadFoundNoSeatSoTheNextRequestMustReadThePoolAgain) {
            return loaded;
        }
        this.seatUsernameByClubIdCachedOnlyAfterAReadThatFoundASeat = loaded;
        return loaded;
    }
}
