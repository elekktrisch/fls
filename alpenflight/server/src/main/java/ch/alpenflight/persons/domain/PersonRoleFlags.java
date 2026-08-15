package ch.alpenflight.persons.domain;

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
