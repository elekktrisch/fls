package ch.alpenflight.persons.application;

/**
 * Command for {@link PersonsService#updateOwnNotificationPrefs} — the
 * caller-scoped per-club notification-prefs self-edit (J-4 T-10, the
 * Notifications tab). Carries ONLY the three notification booleans of the
 * caller's caller-tenant {@link ch.alpenflight.persons.domain.PersonClub}.
 *
 * <p>Deliberately absent: the admin-only membership identity fields
 * (memberNumber / memberState / role flags / isActive). Binding to this command
 * rather than the whole membership shape makes mass-assignment of those fields
 * structurally impossible.
 */
public record SelfNotificationPrefsUpdate(
        boolean receiveFlightReports,
        boolean receiveAircraftReservationNotifications,
        boolean receivePlanningDayRoleReminder) {}
