package ch.alpenflight.clubs.domain;

/**
 * Signal that a club key collides with an existing row — the {@code
 * ux_club_key} UNIQUE index (V2) is the source of truth; the violation is
 * discriminated from the slug case in {@code ClubsService#persist} (J-26
 * T-07). Translated to HTTP 409 with problem-detail {@code field=clubKey}
 * by {@code ClubsExceptionHandler} in {@code clubs.web}; the domain
 * exception stays free of Spring web imports per ADR 0023.
 */
public class ClubKeyAlreadyExistsException extends RuntimeException {

    public ClubKeyAlreadyExistsException(String clubKey) {
        super("Club key already in use: " + clubKey);
    }
}
