package ch.alpenflight.persons.application;

import ch.alpenflight.persons.domain.PersonClub;

public record SelfNotificationPrefsView(
        boolean receiveFlightReports,
        boolean receiveAircraftReservationNotifications,
        boolean receivePlanningDayRoleReminder) {

    public static SelfNotificationPrefsView of(PersonClub pc) {
        return new SelfNotificationPrefsView(
                pc.isReceiveFlightReports(),
                pc.isReceiveAircraftReservationNotifications(),
                pc.isReceivePlanningDayRoleReminder());
    }
}
