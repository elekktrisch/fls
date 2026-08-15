package ch.alpenflight.planning.domain;

import java.util.UUID;

public class PlanningDayNotFoundException extends RuntimeException {

    public PlanningDayNotFoundException(UUID id) {
        super("No active planning day with id " + id + " in the tenant");
    }
}
