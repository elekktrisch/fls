package ch.alpenflight.tenancy.sandbox.domain;

public class NoDemoSeatAvailableException extends RuntimeException {

    public enum Reason {

        EVERY_DEMO_SEAT_IS_IN_USE(
                "All demo seats are in use. Please try again later."),

        THIS_ADDRESS_ALREADY_HOLDS_A_LIVE_DEMO_SEAT(
                "Your address holds a demo session already. Use that session, or wait until it "
                        + "expires."),

        TOO_MANY_VISITORS_CLAIMED_A_SEAT_AT_THE_SAME_MOMENT(
                "Too many visitors started a demo at the same moment. Please try again.");

        private final String readableReason;

        Reason(String readableReason) {
            this.readableReason = readableReason;
        }

        public String readableReason() {
            return this.readableReason;
        }
    }

    private final Reason reason;

    public NoDemoSeatAvailableException(Reason reason) {
        super(reason.name() + ": " + reason.readableReason());
        this.reason = reason;
    }

    public Reason reason() {
        return this.reason;
    }

    public String readableReason() {
        return this.reason.readableReason();
    }
}
