package ch.alpenflight.persons.domain;

public record PersonNotificationPrefs(
        boolean receiveFlightReports,
        boolean receiveAircraftReservationNotifications,
        boolean receivePlanningDayRoleReminder) {

    public static PersonNotificationPrefs none() {
        return new PersonNotificationPrefs(false, false, false);
    }
}
