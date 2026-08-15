package ch.alpenflight.persons.application;

public record SelfNotificationPrefsUpdate(
        boolean receiveFlightReports,
        boolean receiveAircraftReservationNotifications,
        boolean receivePlanningDayRoleReminder) {}
