package ch.alpenflight.flighttypes.domain;

/**
 * Thrown when a FlightType would require an instructor AND an
 * observer-pilot-or-instructor at the same time. The two crew requirements
 * are mutually exclusive: "observer pilot OR instructor required" is the
 * weaker form, so combining it with the strict "instructor required" is
 * contradictory. Legacy enforced this via the DB CHECK
 * {@code CK_FlightTypes_InstructorRequiredXORObserverPilotRequired}
 * (allows (0,0)/(0,1)/(1,0), forbids (1,1)); per ADR 0022 directive 2 the
 * rule lives on the aggregate here — the schema stays structural.
 */
public class InstructorObserverExclusionException extends RuntimeException {

    public InstructorObserverExclusionException() {
        super("instructorRequired and observerPilotOrInstructorRequired are mutually exclusive");
    }
}
