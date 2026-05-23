package ch.alpenflight.aircraft.domain;

/**
 * Service-layer signal that an immatriculation collides with another active
 * Aircraft. Uniqueness is <strong>global</strong> (regulator-convention; see
 * V3 {@code ux_aircraft_immatriculation} partial unique WHERE
 * {@code deleted_on IS NULL}) — intentionally crosses tenant boundaries
 * (Aircraft is tenant-scoped via {@code managing_club_id} per S-159, but
 * immatriculation uniqueness is regulator-global, not per-tenant).
 * Translated to HTTP 409 by {@code AircraftsExceptionHandler}.
 */
public class DuplicateImmatriculationException extends RuntimeException {

    public DuplicateImmatriculationException(String immatriculation) {
        super("Aircraft immatriculation already in use: " + immatriculation);
    }
}
