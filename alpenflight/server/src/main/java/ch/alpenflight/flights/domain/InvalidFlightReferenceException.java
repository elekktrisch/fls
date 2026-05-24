package ch.alpenflight.flights.domain;

import java.util.UUID;

/**
 * Thrown when a referenced FK row (Aircraft / Location / FlightType /
 * FlightCostBalanceType / StartType / Person / FlightCrewType) cannot be
 * resolved at create / update time. Mapped to HTTP 400 by the advice — the
 * caller passed a syntactically valid id that does not exist (or is
 * invisible under the caller's tenant scope).
 */
public class InvalidFlightReferenceException extends RuntimeException {

    public InvalidFlightReferenceException(String referenceKind, UUID id) {
        super("Referenced " + referenceKind + " " + id + " does not exist");
    }

    public InvalidFlightReferenceException(String message) {
        super(message);
    }
}
