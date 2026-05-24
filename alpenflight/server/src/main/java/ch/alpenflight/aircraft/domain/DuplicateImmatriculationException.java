package ch.alpenflight.aircraft.domain;

/**
 * Service-layer signal that an immatriculation collides with another active
 * Aircraft. Uniqueness is <strong>global</strong> (regulator-convention; see
 * V3 {@code ux_aircraft_immatriculation} partial unique WHERE
 * {@code deleted_on IS NULL}). Aircraft is cross-tenant (S-058 reversion
 * of S-159), so the application pre-check sees every other club's
 * immatriculations and surfaces collisions before the DB does.
 * Translated to HTTP 409 by {@code AircraftsExceptionHandler}.
 */
public class DuplicateImmatriculationException extends RuntimeException {

    public DuplicateImmatriculationException(String immatriculation) {
        super("Aircraft immatriculation already in use: " + immatriculation);
    }
}
