package ch.alpenflight.locations.domain;

/**
 * Service-layer signal that an ICAO code collides with another active
 * Location <em>in the same club</em>. Translated to HTTP 409 by
 * {@code LocationsExceptionHandler} in {@code locations.web}; the domain
 * exception stays free of Spring web imports per ADR 0023.
 *
 * <p>Uniqueness is per-club + partial: matches the V7 partial UNIQUE
 * {@code ux_location_club_icao} on {@code (club_id, icao_code)}
 * scoped {@code WHERE icao_code IS NOT NULL AND deleted_on IS NULL}.
 */
public class IcaoCodeAlreadyExistsException extends RuntimeException {

    public IcaoCodeAlreadyExistsException(String icaoCode) {
        super("Location ICAO code already in use within this club: " + icaoCode);
    }
}
