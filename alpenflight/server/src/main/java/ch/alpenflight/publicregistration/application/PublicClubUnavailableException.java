package ch.alpenflight.publicregistration.application;

public class PublicClubUnavailableException extends RuntimeException {

    public enum Reason {
        UNKNOWN,
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
