package ch.alpenflight.planning.domain;

public class InvalidPlanningDateException extends RuntimeException {

    public InvalidPlanningDateException(String message) {
        super(message);
    }
}
