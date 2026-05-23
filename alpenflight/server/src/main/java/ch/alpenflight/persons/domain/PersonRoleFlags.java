package ch.alpenflight.persons.domain;

/**
 * Per-club role flags carried on each {@link PersonClub}. Independent: a
 * pilot can hold any combination (a glider instructor with a motor licence
 * is the common case). Per ADR 0022 directive 2, no DB CHECK enforces a
 * combination — the value-object documents the shape; the schema stays
 * structural.
 */
public record PersonRoleFlags(
        boolean motorPilot,
        boolean towPilot,
        boolean gliderInstructor,
        boolean gliderPilot,
        boolean gliderTrainee,
        boolean passenger,
        boolean winchOperator,
        boolean motorInstructor) {

    public static PersonRoleFlags none() {
        return new PersonRoleFlags(false, false, false, false, false, false, false, false);
    }
}
