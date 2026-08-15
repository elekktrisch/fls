package ch.alpenflight.flighttypes.domain;

public class InstructorObserverExclusionException extends RuntimeException {

    public InstructorObserverExclusionException() {
        super("instructorRequired and observerPilotOrInstructorRequired are mutually exclusive");
    }
}
