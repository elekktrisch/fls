package ch.alpenflight.publicregistration.application;

/** Which of the two public flight-experience flows a submission belongs to. */
public enum PublicRegistrationKind {

    /** A prospective glider pilot's first lesson — the registrant is a trainee. */
    DISCOVERY_FLIGHT(true),

    /** A sightseeing passenger flight — the registrant is not learning to fly. */
    SCENIC_FLIGHT(false);

    private final boolean marksGliderTrainee;

    PublicRegistrationKind(boolean marksGliderTrainee) {
        this.marksGliderTrainee = marksGliderTrainee;
    }

    /**
     * Whether a registrant of this flow is recorded as a glider trainee. Drives
     * BOTH markers legacy sets independently — the person-level
     * {@code has_glider_trainee_licence} and the club-level
     * {@code is_glider_trainee} role — so the two can never diverge.
     */
    public boolean marksGliderTrainee() {
        return marksGliderTrainee;
    }
}
