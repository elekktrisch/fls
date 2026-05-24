package ch.alpenflight.flights.domain;

/**
 * Thrown when {@link Flight#linkTow} is called with an invalid pairing —
 * the V3 schema CHECKs were stripped per ADR 0022 directive 2 and these
 * invariants live on the aggregate:
 *
 * <ul>
 *   <li>Caller must be {@link FlightAircraftType#GLIDER}.</li>
 *   <li>Target must be {@link FlightAircraftType#TOW}.</li>
 *   <li>Caller and target are distinct rows.</li>
 *   <li>Caller and target share the same {@code operatingClubId}.</li>
 * </ul>
 */
public class InvalidTowLinkException extends RuntimeException {

    public InvalidTowLinkException(String message) {
        super(message);
    }
}
