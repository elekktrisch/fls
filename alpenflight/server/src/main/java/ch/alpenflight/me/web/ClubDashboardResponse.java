package ch.alpenflight.me.web;

import ch.alpenflight.flights.application.ClubFlightCounts;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Club-admin dashboard tile counts (caller's club only).")
@JsonInclude(JsonInclude.Include.ALWAYS)
record ClubDashboardResponse(
        @Schema(description = "Count of the caller club's flights flown today (flight_date = today).")
        long todaysFlights,
        @Schema(description = "Count of the caller club's flights pending validation "
                + "(process state NotProcessed or Invalid).")
        long pendingValidation) {

    static ClubDashboardResponse from(ClubFlightCounts counts) {
        return new ClubDashboardResponse(counts.todaysFlights(), counts.pendingValidation());
    }
}
