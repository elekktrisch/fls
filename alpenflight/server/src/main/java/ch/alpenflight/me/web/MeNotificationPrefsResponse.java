package ch.alpenflight.me.web;

import ch.alpenflight.persons.application.SelfNotificationPrefsView;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Caller's own per-club notification preferences (read shape for the Notifications tab).")
record MeNotificationPrefsResponse(
        boolean receiveFlightReports,
        boolean receiveAircraftReservationNotifications,
        boolean receivePlanningDayRoleReminder) {

    static MeNotificationPrefsResponse from(SelfNotificationPrefsView v) {
        return new MeNotificationPrefsResponse(
                v.receiveFlightReports(),
                v.receiveAircraftReservationNotifications(),
                v.receivePlanningDayRoleReminder());
    }
}
