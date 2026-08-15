package ch.alpenflight.me.web;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

@Schema(description = "Per-club notification-prefs self-edit payload — caller's own three toggles only.")
record MeNotificationPrefsUpdateRequest(
        @Schema(description = "Receive flight reports (absent = false).")
        @Nullable Boolean receiveFlightReports,
        @Schema(description = "Receive aircraft-reservation notifications (absent = false).")
        @Nullable Boolean receiveAircraftReservationNotifications,
        @Schema(description = "Receive planning-day role reminders (absent = false).")
        @Nullable Boolean receivePlanningDayRoleReminder) {}
