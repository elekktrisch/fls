package ch.alpenflight.persons.application;

import ch.alpenflight.persons.domain.PersonClub;

/**
 * Read projection of the caller's own per-club notification preferences —
 * returned by the caller-scoped {@code GET /api/v1/me/club-membership/notification-prefs}
 * (so the Notifications tab, J-4 T-11, hydrates) and ALSO used as the lean,
 * before/after snapshot handed to the audit trail on the prefs self-edit
 * (J-4 T-10).
 *
 * <p>Projects exactly the three notification booleans
 * {@link PersonClub#updateNotificationPrefs} can mutate — deliberately NOT the
 * admin-only membership identity fields (memberNumber / memberState / role
 * flags / isActive), which the self-edit surface never touches. The audit
 * listener keys redaction off the {@code entityType} string the service passes
 * ({@code "PersonClubNotificationPrefs"}); that entity type carries an explicit
 * allow-list so the before/after diff is READABLE (the booleans are
 * non-sensitive operational flags).
 */
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
