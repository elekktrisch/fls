package ch.alpenflight.locations.domain;

/**
 * Raised when a non-null ICAO code fails the
 * {@code ^[A-Z]{4}$|^[A-Z]{2}[0-9]{2}$} format invariant on the
 * {@code Location} aggregate. Translated to HTTP 400 by
 * {@code LocationsExceptionHandler} in {@code locations.web}.
 *
 * <p>Legacy was lax on ICAO formatting; the rewrite tightens at the API
 * boundary (Bean Validation) and again on the aggregate (defence in depth
 * per ADR 0022 directive 2 — business rules live in the aggregate, not in
 * a DB CHECK constraint).
 */
public class IcaoCodeInvalidException extends RuntimeException {

    public IcaoCodeInvalidException(String icaoCode) {
        super("ICAO code must match ^[A-Z]{4}$|^[A-Z]{2}[0-9]{2}$, got: " + icaoCode);
    }
}
