package ch.alpenflight.flights.domain;

import ch.alpenflight.platform.id.FlightId;
import java.util.List;
import java.util.Objects;

/**
 * Cross-aggregate invariants on the glider→tow link that can't sit inside
 * {@link Flight#linkTow} because they need to consult state on other Flight
 * rows. Lives in the domain layer per ADR 0022 directive 2: the rule
 * belongs to the model, not to whichever service happens to call it.
 *
 * <p>Each future caller (S-077 rules engine, S-083 daily-validation job,
 * S-105 depth corpus) routes through this class — there is no second
 * implementation of the rule.
 */
public final class TowLinkPolicy {

    private TowLinkPolicy() {}

    /**
     * Reject a tow already linked by a non-deleted glider other than the
     * caller. Cascade-delete of the first glider would otherwise silently
     * orphan the second link.
     */
    public static void verifyExclusiveLink(FlightId towId,
                                           List<Flight> existingLinkers,
                                           Flight self) {
        for (Flight other : existingLinkers) {
            if (!Objects.equals(other.getId(), self.getId())) {
                throw new InvalidTowLinkException(
                        "Tow flight " + towId.toExternal()
                                + " is already linked by another glider");
            }
        }
    }
}
