package ch.alpenflight.me.web;

import ch.alpenflight.persons.application.SelfNotificationPrefsView;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Wire response for {@code GET /api/v1/me/club-membership/notification-prefs} —
 * the caller's own per-club notification preferences (J-4 T-10), so the
 * Notifications tab (T-11) hydrates. Mirrors the
 * {@link MeNotificationPrefsUpdateRequest} field set exactly: GET returns it,
 * PATCH replaces it.
 */
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
