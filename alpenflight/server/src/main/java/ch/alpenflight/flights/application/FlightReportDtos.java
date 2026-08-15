package ch.alpenflight.flights.application;

import ch.alpenflight.flights.domain.FlightCategory;
import ch.alpenflight.platform.id.FlightId;
import ch.alpenflight.platform.id.LocationId;
import ch.alpenflight.platform.id.PersonId;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

public final class FlightReportDtos {

    private FlightReportDtos() {}

    @Schema(description = "Flight-report filter criteria.")
    public record FlightReportFilter(
            @Nullable LocalDate flightDateFrom,
            @Nullable LocalDate flightDateTo,
            @Nullable PersonId flightCrewPersonId,
            @Nullable LocationId locationId,
            boolean gliderFlights,
            boolean motorFlights,
            boolean towFlights) {

        public static FlightReportFilter defaults() {
            return new FlightReportFilter(null, null, null, null, true, true, false);
        }
    }

    @Schema(description = "Paged flight-report result: data rows + total-row count + (T-04) summaries.")
    public record FlightReportResult(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<FlightReportDataRecord> items,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long totalRows,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<FlightReportSummary> summaries) {}

    @Schema(description = "Flight-report summary row (one crew-function/flight-type group, plus a Total row).")
    public record FlightReportSummary(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String groupBy,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int totalStarts,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int totalLdgs,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int totalFlights,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Duration totalFlightDuration) {}

    @Schema(description = "One flight-report data row (one row per flight).")
    public record FlightReportDataRecord(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) FlightId flightId,
            @Nullable LocalDate flightDate,
            @Nullable String immatriculation,
            @Nullable String pilotName,
            @Nullable String secondCrewName,
            @Nullable String flightComment,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int airState,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int processState,
            @Nullable String flightCode,
            @Nullable String flightTypeName,
            @Nullable Instant startDateTime,
            @Nullable Instant ldgDateTime,
            @Nullable Duration flightDuration,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isSoloFlight,
            @Nullable Integer startType,
            @Nullable String startLocation,
            @Nullable String ldgLocation,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) FlightCategory flightCategory,
            @Nullable FlightId towedGliderFlightId,
            @Nullable TowFlightReportDataRecord towFlight) {}

    @Schema(description = "Nested tow-flight block under an aerotow glider row.")
    public record TowFlightReportDataRecord(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) FlightId towFlightId,
            @Nullable String immatriculation,
            @Nullable String pilotName,
            @Nullable String flightCode,
            @Nullable String flightTypeName,
            @Nullable Instant startDateTime,
            @Nullable Instant ldgDateTime,
            @Nullable String startLocation,
            @Nullable String ldgLocation,
            @Nullable Duration flightDuration,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int airState,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int processState) {}
}
