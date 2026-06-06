package ch.alpenflight.me.web;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * Wire payload for {@code PATCH /api/v1/me/club-membership/notification-prefs} —
 * the caller-scoped per-club notification-prefs self-edit (J-4 T-10, the
 * Notifications tab). Carries ONLY the three notification booleans of the
 * caller's caller-tenant {@link ch.alpenflight.persons.domain.PersonClub}.
 *
 * <p>The booleans are nullable {@code Boolean} (not primitives) so an absent
 * flag deserialises cleanly to {@code null} → coerced to {@code false} in the
 * controller ({@code Boolean.TRUE.equals(...)}, the same pattern
 * {@link MePersonLicencesUpdateRequest} uses — needed because Jackson's
 * {@code FAIL_ON_NULL_FOR_PRIMITIVES} is on). The tab POSTs the full prefs shape
 * (all three toggles), so an unchecked toggle still lands as {@code false} —
 * "replace the whole prefs set" semantics.
 *
 * <p>Deliberately absent: the admin-only membership identity fields
 * (memberNumber / memberState / role flags / isActive). Binding to this record
 * rather than the membership shape makes mass-assignment of those fields
 * structurally impossible — they stay admin-only.
 */
@Schema(description = "Per-club notification-prefs self-edit payload — caller's own three toggles only.")
record MeNotificationPrefsUpdateRequest(
        @Schema(description = "Receive flight reports (absent = false).")
        @Nullable Boolean receiveFlightReports,
        @Schema(description = "Receive aircraft-reservation notifications (absent = false).")
        @Nullable Boolean receiveAircraftReservationNotifications,
        @Schema(description = "Receive planning-day role reminders (absent = false).")
        @Nullable Boolean receivePlanningDayRoleReminder) {}
