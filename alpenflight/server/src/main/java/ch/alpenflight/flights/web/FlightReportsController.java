package ch.alpenflight.flights.web;

import ch.alpenflight.audit.domain.ReadOnlyQuery;
import ch.alpenflight.flights.application.FlightReportDtos.FlightReportFilter;
import ch.alpenflight.flights.application.FlightReportDtos.FlightReportResult;
import ch.alpenflight.flights.application.FlightReportQueryService;
import ch.alpenflight.platform.id.LocationId;
import ch.alpenflight.platform.id.PersonId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * REST surface for the flight-report read model (J-7 T-05) — the SPA paged
 * report query. Path {@code /api/v1/flightreports} (ADR 0005), mirroring the
 * legacy {@code FlightReportsController.cs:40-46} {@code POST page/{start}/{size}}
 * shape with a {@code PageableSearchFilter}-style body.
 *
 * <p>The Excel-export endpoint ({@code POST .../export/excel/{start}/{size}},
 * T-07) shares this resource: same body + tenant scoping + authz, but streams an
 * {@code .xlsx} attachment ({@link FlightReportExcelWriter}, the exact legacy
 * layout) instead of JSON.
 *
 * <p><strong>Tenant scoping.</strong> Every query is scoped to the caller's
 * tenant by {@link FlightReportQueryService} (it reads the {@code clubId} the
 * request filter sets on {@code TenantContextCarrier}) — correcting the legacy
 * tenancy hole ({@code FlightReportService.cs:114-125}). A club-A caller
 * filtering by a club-B location sees no club-B flights.
 *
 * <p><strong>Authz.</strong> Mirrors {@link FlightsController#list} post the
 * J-3 PILOT-403 fix: {@code CLUB_ADMINISTRATOR}, {@code FLIGHT_OPERATOR}, or
 * {@code PILOT}. A report reads flight rows the same tenant-scoped row set the
 * flights list exposes, so a PILOT must be able to read their own/club report
 * (the J-3 lesson — do not over-restrict to admin-only). No
 * {@code SYSTEM_ADMINISTRATOR} on this tenant-scoped read (S-159).
 *
 * <p>The page POST carries its filter in the body (legacy
 * {@code PageableSearchFilter} bodies don't fit a {@code GET} query string), so
 * it is a read-shaped POST: {@link ReadOnlyQuery} opts it out of the
 * mutating-verb audit guard — it changes no state and emits no audit event.
 *
 * <p>Each endpoint carries an explicit {@code @Operation(operationId=...)} so
 * orval generates a stable named client method (not a positional {@code postN}
 * that renumbers when endpoints are added) — the J-3 orval-stability rider.
 */
@RestController
@RequestMapping(path = "/api/v1/flightreports", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "flightreports", description = "Flight reports (paged read-side report query).")
class FlightReportsController {

    /**
     * The correct OOXML spreadsheet MIME for an {@code .xlsx} body. Legacy sent
     * the wrong {@code application/vnd.ms-excel} (the old binary {@code .xls}
     * type) for its {@code .xlsx} export — a documented J-7 deviation, corrected
     * here. Parity-harness-neutral: the MIME is not cell content.
     */
    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** Attachment filename for the streamed export. */
    private static final String EXPORT_FILENAME = "FlightReports.xlsx";

    private final FlightReportQueryService reports;

    FlightReportsController(FlightReportQueryService reports) {
        this.reports = reports;
    }

    /**
     * SPA paged flight report — the legacy {@code POST page/{pageStart}/{pageSize}}
     * shape. {@code pageStart} is a 0-based ROW offset (not a page number);
     * {@code pageSize} defaults to 100 and is capped at 500 by the query service.
     * Body is the {@code PageableSearchFilter}-style {@code {sorting, searchFilter}}
     * envelope (both optional — an empty body runs the legacy-default filter:
     * glider+motor on, tow off).
     */
    @Operation(operationId = "getFlightReportPage",
            summary = "Paged flight report (legacy PageableSearchFilter shape). Body carries optional "
                    + "sorting (`FlightDuration: asc|desc`) + a flight-report search filter. "
                    + "pageStart is a 0-based row offset; pageSize defaults to 100, capped at 500.")
    @ApiResponse(responseCode = "200", description = "One page of report rows + total-row count + summaries.")
    @PostMapping(path = "/page/{pageStart}/{pageSize}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('CLUB_ADMINISTRATOR', 'FLIGHT_OPERATOR', 'PILOT')")
    @ReadOnlyQuery
    FlightReportResult getFlightReportPage(
            @PathVariable("pageStart") int pageStart,
            @PathVariable("pageSize") int pageSize,
            @RequestBody(required = false) @Nullable FlightReportPageRequest request) {
        return runReport(request, pageStart, pageSize);
    }

    /**
     * Runs the report query for the shared {@code {sorting, searchFilter}} body —
     * the common preamble behind both the JSON page endpoint and the Excel export
     * (null body ⇒ legacy-default filter; paging/sort folded from the request).
     */
    private FlightReportResult runReport(@Nullable FlightReportPageRequest request,
                                         int pageStart, int pageSize) {
        FlightReportPageRequest req = request != null ? request : FlightReportPageRequest.empty();
        FlightReportFilter filter = req.toFilter();
        return reports.getReportPage(filter, pageStart, pageSize,
                req.sortByDuration(), req.sortAscending());
    }

    /**
     * Synchronous Excel export of the flight report — same body + tenant scoping
     * + authz as {@link #getFlightReportPage}, but returns a streamed
     * {@code .xlsx} attachment in the exact legacy layout
     * ({@link FlightReportExcelWriter}, oracle §5). The workbook is streamed to
     * the response output stream via SXSSF — the whole file is never buffered in
     * memory.
     *
     * <p>Read-shaped (it only reads the same tenant-scoped row set), so
     * {@link ReadOnlyQuery} exempts it from the mutating-verb audit guard. The
     * {@code Content-Type} is the corrected OOXML spreadsheet MIME (legacy sent
     * the wrong {@code application/vnd.ms-excel}) — a documented, harness-neutral
     * J-7 deviation.
     */
    @Operation(operationId = "exportFlightReportExcel",
            summary = "Synchronous Excel (.xlsx) export of the flight report. Same body as the page "
                    + "endpoint; streams an attachment in the exact legacy 30-column layout. "
                    + "pageStart is a 0-based row offset; pageSize defaults to 100, capped at 500.")
    @ApiResponse(responseCode = "200", description = "Streamed .xlsx attachment (Flights sheet).")
    @PostMapping(path = "/export/excel/{pageStart}/{pageSize}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = XLSX_MIME)
    @PreAuthorize("hasAnyRole('CLUB_ADMINISTRATOR', 'FLIGHT_OPERATOR', 'PILOT')")
    @ReadOnlyQuery
    ResponseEntity<StreamingResponseBody> exportFlightReportExcel(
            @PathVariable("pageStart") int pageStart,
            @PathVariable("pageSize") int pageSize,
            @RequestBody(required = false) @Nullable FlightReportPageRequest request) {
        FlightReportResult result = runReport(request, pageStart, pageSize);
        Instant generatedAt = Instant.now();

        StreamingResponseBody body = out -> FlightReportExcelWriter.write(result, generatedAt, out);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, XLSX_MIME)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + EXPORT_FILENAME + "\"")
                .body(body);
    }

    /**
     * Legacy {@code PageableSearchFilter<FlightReportFilterCriteria>} envelope:
     * a column→direction {@code sorting} map + a {@code searchFilter}. The only
     * sort key the read model honours is {@code FlightDuration} (asc default;
     * {@code desc} flips) — every other key falls back to the default sort
     * (oracle §8.45: default {@code StartDateTime asc, Immatriculation asc}).
     */
    @Schema(description = "Legacy PageableSearchFilter-shaped flight-report request (sorting + filter criteria).")
    record FlightReportPageRequest(
            @Nullable @Schema(description = "Column→direction map; only `FlightDuration: asc|desc` is honoured.")
                    Map<String, String> sorting,
            @Nullable FlightReportSearchFilter searchFilter) {

        static FlightReportPageRequest empty() {
            return new FlightReportPageRequest(null, null);
        }

        /** True when the {@code sorting} map names {@code FlightDuration} as the sort key. */
        boolean sortByDuration() {
            return sorting != null && sorting.containsKey("FlightDuration");
        }

        /** Sort direction — ascending unless the (FlightDuration) value is {@code desc}. */
        boolean sortAscending() {
            if (sorting == null) {
                return true;
            }
            String dir = sorting.get("FlightDuration");
            return dir == null || !"desc".equalsIgnoreCase(dir.trim());
        }

        /** Folds the request filter to the service-layer {@link FlightReportFilter}, applying defaults. */
        FlightReportFilter toFilter() {
            FlightReportSearchFilter f = searchFilter != null
                    ? searchFilter
                    : FlightReportSearchFilter.empty();
            return f.toFilter();
        }
    }

    /**
     * Mirrors the legacy {@code FlightReportFilterCriteria.cs:6-19}. The three
     * flight-type flags default per oracle §8.45 when omitted from the body:
     * {@code gliderFlights}/{@code motorFlights} default {@code true},
     * {@code towFlights} defaults {@code false}. {@code flightDateFrom}/
     * {@code flightDateTo} are inclusive date-only bounds (legacy
     * {@code DateTimeFilter FlightDate}, flattened).
     */
    @Schema(description = "Flight-report filter criteria (legacy FlightReportFilterCriteria; type flags default on/on/off).")
    record FlightReportSearchFilter(
            @Nullable LocalDate flightDateFrom,
            @Nullable LocalDate flightDateTo,
            @Nullable PersonId flightCrewPersonId,
            @Nullable LocationId locationId,
            @Nullable Boolean gliderFlights,
            @Nullable Boolean motorFlights,
            @Nullable Boolean towFlights) {

        static FlightReportSearchFilter empty() {
            return new FlightReportSearchFilter(null, null, null, null, null, null, null);
        }

        FlightReportFilter toFilter() {
            return new FlightReportFilter(
                    flightDateFrom,
                    flightDateTo,
                    flightCrewPersonId,
                    locationId,
                    gliderFlights == null || gliderFlights,
                    motorFlights == null || motorFlights,
                    towFlights != null && towFlights);
        }
    }
}
