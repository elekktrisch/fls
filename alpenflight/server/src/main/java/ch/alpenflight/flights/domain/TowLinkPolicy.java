package ch.alpenflight.flights.domain;

import ch.alpenflight.platform.id.FlightId;
import java.util.List;
import java.util.Objects;

public final class TowLinkPolicy {

    private TowLinkPolicy() {}

    public static void verifyExclusiveLink(FlightId towId,
                                           List<Flight> liveGlidersAlreadyLinkingThisTow,
                                           Flight self) {
        for (Flight other : liveGlidersAlreadyLinkingThisTow) {
            if (!Objects.equals(other.getId(), self.getId())) {
                throw new InvalidTowLinkException(
                        "Tow flight " + towId.toExternal()
                                + " is already linked by another glider");
            }
        }
    }
}
