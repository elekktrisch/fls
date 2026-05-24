package ch.alpenflight.persons.domain;

/**
 * Per-club notification preferences carried on each {@link PersonClub}.
 * Independent flags; default-off.
 */
public record PersonNotificationPrefs(
        boolean receiveFlightReports,
        boolean receiveAircraftReservationNotifications,
        boolean receivePlanningDayRoleReminder) {

    public static PersonNotificationPrefs none() {
        return new PersonNotificationPrefs(false, false, false);
    }
}
