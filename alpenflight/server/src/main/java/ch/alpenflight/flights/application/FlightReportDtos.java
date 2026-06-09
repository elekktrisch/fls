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

/**
 * DTOs for the flight-report read model (J-7). Records (immutable) mirroring
 * the legacy {@code FLS.Data.WebApi.Reporting.Flights} shape
 * ({@code FlightReportResult.cs}, {@code FlightReportDataRecord.cs},
 * {@code TowFlightReportDataRecord.cs}, {@code FlightReportFilterCriteria.cs}),
 * with the J-7 parity corrections applied (see the journey's "Parity decisions"
 * note):
 *
 * <ul>
 *   <li>Tenant-scoped on every query path (ADR 0008) — corrects the legacy
 *       tenancy hole; a foreign location filter cannot leak other clubs'
 *       flights.</li>
 *   <li>{@code AirState} / {@code processState} are emitted as the legacy
 *       SMALLINT codes (computed air-state, persisted process-state) so the
 *       wire / Excel shape matches.</li>
 *   <li>{@code summaries} is present-but-empty here (T-03); T-04 fills it.</li>
 * </ul>
 *
 * <p>{@code FlightDuration} is computed from {@code startDateTime} /
 * {@code ldgDateTime} (legacy {@code DbFunctions.DiffSeconds}); null when
 * either bound is absent.
 */
public final class FlightReportDtos {

    private FlightReportDtos() {}

    /**
     * Filter criteria for a flight-report page. Mirrors the legacy
     * {@code FlightReportFilterCriteria}. {@code from}/{@code to} are
     * date-only bounds (inclusive); null {@code from} ⇒ unbounded below,
     * null {@code to} ⇒ unbounded above; flights with a null
     * {@code flight_date} are excluded once a window is given. All three
     * type flags false ⇒ no flights match.
     */
    @Schema(description = "Flight-report filter criteria.")
    public record FlightReportFilter(
            @Nullable LocalDate flightDateFrom,
            @Nullable LocalDate flightDateTo,
            @Nullable PersonId flightCrewPersonId,
            @Nullable LocationId locationId,
            boolean gliderFlights,
            boolean motorFlights,
            boolean towFlights) {

        /** Legacy defaults: glider + motor on, tow off. */
        public static FlightReportFilter defaults() {
            return new FlightReportFilter(null, null, null, null, true, true, false);
        }
    }

    @Schema(description = "Paged flight-report result: data rows + total-row count + (T-04) summaries.")
    public record FlightReportResult(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<FlightReportDataRecord> items,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long totalRows,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<FlightReportSummary> summaries) {}

    /**
     * One aggregated summary row (legacy {@code FlightReportSummary.cs:6-37}).
     * Two mutually-exclusive grouping branches populate this (T-04):
     *
     * <ul>
     *   <li><b>Person branch</b> (filter carries a {@code flightCrewPersonId}) —
     *       up to 6 fixed-order rows ({@code Pilot (Glider)}, {@code Pilot
     *       (Motor)}, {@code Pilot (Towing)}, {@code Copilot}, {@code Instructor},
     *       {@code Instructor (Soloflights)}), each present only when it has ≥1
     *       flight, plus a {@code Total} row always appended.</li>
     *   <li><b>Location branch</b> (filter carries a {@code locationId} but no
     *       person) — one row per {@code FlightTypeName} (alphabetical) plus a
     *       {@code Total} row.</li>
     * </ul>
     *
     * <p>{@code totalStarts}/{@code totalLdgs} are derived from landings (legacy
     * has no starts column); the formulas are reproduced exactly from
     * {@code FlightReportService.cs:188-730} — including the INTENDED quirk that
     * person-branch {@code totalStarts} uses the {@code nrOfLdgs} base. The J-7
     * parity correction sets {@code totalFlights} on ALL rows (legacy omits it on
     * {@code Pilot (Motor)}/{@code Pilot (Towing)} → 0, under-counting the Total).
     */
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
