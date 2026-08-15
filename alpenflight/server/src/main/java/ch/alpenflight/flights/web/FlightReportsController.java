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

@RestController
@RequestMapping(path = "/api/v1/flightreports", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "flightreports", description = "Flight reports (paged read-side report query).")
class FlightReportsController {

    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final String EXPORT_FILENAME = "FlightReports.xlsx";

    private final FlightReportQueryService reports;

    FlightReportsController(FlightReportQueryService reports) {
        this.reports = reports;
    }

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

    private FlightReportResult runReport(@Nullable FlightReportPageRequest request,
                                         int pageStart, int pageSize) {
        FlightReportPageRequest req = request != null ? request : FlightReportPageRequest.empty();
        FlightReportFilter filter = req.toFilter();
        return reports.getReportPage(filter, pageStart, pageSize,
                req.sortByDuration(), req.sortAscending());
    }

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

    @Schema(description = "Legacy PageableSearchFilter-shaped flight-report request (sorting + filter criteria).")
    record FlightReportPageRequest(
            @Nullable @Schema(description = "Column→direction map; only `FlightDuration: asc|desc` is honoured.")
                    Map<String, String> sorting,
            @Nullable FlightReportSearchFilter searchFilter) {

        private static final String ONLY_HONOURED_SORT_KEY = "FlightDuration";
        private static final String DESCENDING_DIRECTION = "desc";

        static FlightReportPageRequest empty() {
            return new FlightReportPageRequest(null, null);
        }

        boolean sortByDuration() {
            return sorting != null && sorting.containsKey(ONLY_HONOURED_SORT_KEY);
        }

        boolean sortAscending() {
            if (sorting == null) {
                return true;
            }
            String dir = sorting.get(ONLY_HONOURED_SORT_KEY);
            return dir == null || !DESCENDING_DIRECTION.equalsIgnoreCase(dir.trim());
        }

        FlightReportFilter toFilter() {
            FlightReportSearchFilter f = searchFilter != null
                    ? searchFilter
                    : FlightReportSearchFilter.empty();
            return f.toFilter();
        }
    }

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
