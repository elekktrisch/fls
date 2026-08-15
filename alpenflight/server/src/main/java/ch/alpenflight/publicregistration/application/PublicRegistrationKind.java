package ch.alpenflight.publicregistration.application;

public enum PublicRegistrationKind {

    DISCOVERY_FLIGHT(true),

    SCENIC_FLIGHT(false);

    private final boolean marksGliderTrainee;

    PublicRegistrationKind(boolean marksGliderTrainee) {
        this.marksGliderTrainee = marksGliderTrainee;
    }

    public boolean marksGliderTrainee() {
        return marksGliderTrainee;
    }
}
