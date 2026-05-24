package ch.alpenflight.flighttypes.domain;

/**
 * Thrown when a {@link FlightCostBalanceType} mutation would violate the
 * "at least one of glider / tow / motor must be true" aggregate invariant.
 * V3 dropped the equivalent {@code ck_fcbt_at_least_one_flag} CHECK per
 * ADR 0022 directive 2; this exception is the Java-side equivalent.
 */
public class FlightCostBalanceTypeInvariantException extends RuntimeException {

    public FlightCostBalanceTypeInvariantException(String message) {
        super(message);
    }
}
