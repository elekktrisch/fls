package ch.alpenflight.planning.domain;

public class PlanningDayConflictException extends RuntimeException {

    public PlanningDayConflictException(String message) {
        super(message);
    }

    public PlanningDayConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
