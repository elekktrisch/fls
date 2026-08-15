package ch.alpenflight.me.web;

import ch.alpenflight.me.application.SystemDashboardTotals;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

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
