package ch.alpenflight.me.web;

import ch.alpenflight.me.application.SystemDashboardTotals;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Wire shape for {@code GET /api/v1/me/system-dashboard} (J-3 T-10) — the
 * sysadmin dashboard's cross-tenant tile totals. All three span EVERY club, the
 * deliberate opposite of {@link ClubDashboardResponse} (which is scoped to the
 * caller's club).
 *
 * @param totalClubs   total active clubs across the deployment
 * @param totalUsers   total active users across all clubs
 * @param totalFlights total non-deleted flights across all clubs
 */
@Schema(description = "Sysadmin dashboard cross-tenant totals (all clubs).")
@JsonInclude(JsonInclude.Include.ALWAYS)
record SystemDashboardResponse(
        @Schema(description = "Total active clubs across the whole deployment.")
        long totalClubs,
        @Schema(description = "Total active users across all clubs.")
        long totalUsers,
        @Schema(description = "Total non-deleted flights across all clubs.")
        long totalFlights) {

    static SystemDashboardResponse from(SystemDashboardTotals totals) {
        return new SystemDashboardResponse(
                totals.totalClubs(), totals.totalUsers(), totals.totalFlights());
    }
}
