package ch.alpenflight.publicregistration.application;

/**
 * The slug in a public-registration URL does not resolve to a club that accepts
 * anonymous submissions. {@link Reason} is the single place the anonymous
 * disclosure contract is decided; {@code PublicRegistrationExceptionHandler}
 * maps it to a status and nothing else.
 */
public class PublicClubUnavailableException extends RuntimeException {

    /**
     * Why the slug was rejected, and — by extension — what an anonymous caller
     * learns from probing it.
     *
     * <p><strong>Enumeration contract (deliberate).</strong> The two reasons map
     * to <em>distinct</em> statuses (404 / 403), so a caller can tell "no such
     * club" from "this club exists but has closed public registration". That is
     * accepted: a club hands its slug out itself, in the very URL that leads to
     * these forms (website links, QR codes, printed flyers), so slug existence is
     * not a secret, and the distinction is what lets a visitor arriving on a
     * stale link be told registration is closed instead of that the club does not
     * exist. Bulk probing is bounded by the IP × slug abuse guard, not by making
     * the two answers indistinguishable. Neither response carries a body, so the
     * status is the only bit disclosed — no club name, id, or count.
     */
    public enum Reason {
        /** No active club is published at that slug — or the slug is malformed. */
        UNKNOWN,
        /** The club exists but {@code public_registration_enabled} is off. */
        REGISTRATION_CLOSED
    }

    private final Reason reason;

    public PublicClubUnavailableException(Reason reason) {
        super("Public registration unavailable: " + reason);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
