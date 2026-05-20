package ch.alpenflight.locations.domain;

/**
 * Service-layer signal that an ICAO code collides with an existing active
 * row. Translated to HTTP 409 by {@code LocationsExceptionHandler} in
 * {@code locations.web}; the domain exception stays free of Spring web
 * imports per ADR 0023.
 *
 * <p>Uniqueness is partial — soft-deleted rows do not block recreation —
 * matching the {@code ux_location_icao} index in V3
 * ({@code WHERE icao_code IS NOT NULL}).
 */
public class IcaoCodeAlreadyExistsException extends RuntimeException {

    public IcaoCodeAlreadyExistsException(String icaoCode) {
        super("Location ICAO code already in use: " + icaoCode);
    }
}
