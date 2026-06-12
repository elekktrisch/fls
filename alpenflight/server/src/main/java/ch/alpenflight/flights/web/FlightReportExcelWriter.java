package ch.alpenflight.flights.web;

import ch.alpenflight.flights.application.FlightReportDtos.FlightReportDataRecord;
import ch.alpenflight.flights.application.FlightReportDtos.FlightReportResult;
import ch.alpenflight.flights.application.FlightReportDtos.TowFlightReportDataRecord;
import ch.alpenflight.platform.excel.ExcelExportSupport;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.jspecify.annotations.Nullable;

/**
 * Streams a {@link FlightReportResult} to an {@code .xlsx} workbook in the exact
 * legacy layout (J-7 T-07, S-095) — the parity contract the harness (T-08)
 * checks. Reproduces {@code FlightReportService.cs:743-859} (EPPlus) byte-for-byte
 * on cell <em>value + number-format</em>:
 *
 * <ul>
 *   <li>Sheet name {@code Flights}.</li>
 *   <li>A1 = {@code Flights} (font 20); row 2 blank; A3 = {@code Excel Erstellt:};
 *       C3 = generation timestamp ({@code dd.mm.yyyy HH:MM:ss}, left-aligned).</li>
 *   <li>Header = row 5 (index 4); data from row 6 (index 5), one row per item.</li>
 *   <li>30 columns in the legacy order with <strong>column 17 intentionally
 *       skipped</strong> (no header, blank cell) and the preserved typo
 *       <strong>{@code LdgTime UCT}</strong> on column 11 (see the journey's
 *       Parity decisions §5).</li>
 *   <li>Tow columns 19-30 written ONLY when the row carries a {@code TowFlight}.</li>
 *   <li>Time cells = UTC wall-clock ({@code HH:MM}, no TZ shift); durations
 *       {@code [H]:MM}; {@code IsSoloFlight} as int 1/0; AirState / ProcessState /
 *       StartType as raw ints (StartType already mapped to the legacy
 *       {@code AircraftStartType} int by the query service).</li>
 *   <li>{@code FlightDate} (col 2) carries NO number format (legacy default).</li>
 * </ul>
 *
 * <p>Stateless — one {@link #write} call streams one result to the response output
 * stream via SXSSF (no whole-file buffering).
 */
final class FlightReportExcelWriter {

    /** Legacy sheet name. */
    static final String SHEET_NAME = "Flights";
    /** Legacy A1 title font size. */
    private static final int TITLE_FONT_SIZE = 20;
    /** Legacy A3/C3 timestamp number format. */
    private static final String TIMESTAMP_FORMAT = "dd.mm.yyyy HH:MM:ss";
    /** 0-based index of the header row (legacy row 5). */
    private static final int HEADER_ROW = 4;
    /** 0-based index of the first data row (legacy row 6). */
    private static final int FIRST_DATA_ROW = 5;
    /** Column count (1..30 with col 17 skipped); autosize span. */
    private static final int COLUMN_COUNT = 30;

    /**
     * Header labels, legacy order. Column 17 (index 16) is intentionally blank —
     * no header, no data. Column 11 ({@code LdgTime UCT}) preserves the legacy
     * typo (UCT not UTC). 0-based array indices = legacy column number − 1.
     */
    private static final String[] HEADERS = {
        "Flight ID",                 // 1
        "FlightDate",                // 2
        "Immatriculation",           // 3
        "PilotName",                 // 4
        "SecondCrewName",            // 5
        "AirState",                  // 6
        "ProcessState",              // 7
        "FlightCode",                // 8
        "FlightTypeName",            // 9
        "StartTime UTC",             // 10
        "LdgTime UCT",               // 11  (preserved typo: UCT)
        "FlightDuration",            // 12
        "IsSoloFlight",              // 13
        "StartType",                 // 14
        "StartLocation",             // 15
        "LdgLocation",               // 16
        "",                          // 17  (intentionally skipped)
        "FlightComment",             // 18
        "TowFlight-FlightId",        // 19
        "TowFlight-Immatriculation", // 20
        "TowFlight-PilotName",       // 21
        "TowFlight-StartTime UTC",   // 22
        "TowFlight-LdgTime UTC",     // 23
        "TowFlight-FlightDuration",  // 24
        "TowFlight-StartLocation",   // 25
        "TowFlight-LdgLocation",     // 26
        "TowFlight-FlightCode",      // 27
        "TowFlight-FlightTypeName",  // 28
        "TowFlight-AirState",        // 29
        "TowFlight-ProcessState",    // 30
    };

    private FlightReportExcelWriter() {}

    /**
     * Streams {@code result} as an {@code .xlsx} workbook to {@code out}.
     * Try-with-resources {@code close()} disposes the SXSSF temp files.
     *
     * @param result    the page of report rows to render (honors page/sort)
     * @param generatedAt the generation timestamp for the C3 metadata cell
     * @param out       the response output stream (not closed here)
     */
    static void write(FlightReportResult result, Instant generatedAt, OutputStream out)
            throws IOException {
        ExcelExportSupport excel = ExcelExportSupport.streamingWorkbook();
        try (SXSSFWorkbook workbook = excel.workbook()) {
            SXSSFSheet sheet = workbook.createSheet(SHEET_NAME);
            excel.trackColumnsForAutoSizing(sheet);

            writeMetadata(excel, sheet, generatedAt);
            writeHeader(excel, sheet);
            writeDataRows(excel, sheet, result);

            excel.autoSize(sheet, COLUMN_COUNT);
            workbook.write(out);
        }
    }

    private static void writeMetadata(ExcelExportSupport excel, SXSSFSheet sheet, Instant generatedAt) {
        // A1 = "Flights" (font 20).
        Row title = excel.dataRow(sheet, 0);
        excel.titleCell(title, 0, SHEET_NAME, TITLE_FONT_SIZE);
        // Row 2 (index 1) blank.
        // Row 3 (index 2): A3 = "Excel Erstellt:", B3 blank, C3 = timestamp.
        Row meta = excel.dataRow(sheet, 2);
        excel.stringCell(meta, 0, "Excel Erstellt:");
        excel.dateCell(meta, 2, utcWallClock(generatedAt), TIMESTAMP_FORMAT);
    }

    private static void writeHeader(ExcelExportSupport excel, SXSSFSheet sheet) {
        excel.headerRow(sheet, HEADER_ROW, HEADERS);
    }

    private static void writeDataRows(ExcelExportSupport excel, SXSSFSheet sheet, FlightReportResult result) {
        int rowIndex = FIRST_DATA_ROW;
        for (FlightReportDataRecord item : result.items()) {
            writeDataRow(excel, excel.dataRow(sheet, rowIndex++), item);
        }
    }

    private static void writeDataRow(ExcelExportSupport excel, Row row, FlightReportDataRecord item) {
        // Columns are 0-based here = legacy column number − 1.
        excel.stringCell(row, 0, idText(item.flightId()));            // 1 Flight ID
        if (item.flightDate() != null) {                              // 2 FlightDate (no format)
            row.createCell(1).setCellValue(item.flightDate().atStartOfDay());
        }
        stringIfPresent(excel, row, 2, item.immatriculation());       // 3 Immatriculation
        stringIfPresent(excel, row, 3, item.pilotName());             // 4 PilotName
        stringIfPresent(excel, row, 4, item.secondCrewName());        // 5 SecondCrewName
        excel.intCell(row, 5, item.airState());                       // 6 AirState (int)
        excel.intCell(row, 6, item.processState());                   // 7 ProcessState (int)
        stringIfPresent(excel, row, 7, item.flightCode());            // 8 FlightCode
        stringIfPresent(excel, row, 8, item.flightTypeName());        // 9 FlightTypeName
        timeIfPresent(excel, row, 9, item.startDateTime());           // 10 StartTime UTC
        timeIfPresent(excel, row, 10, item.ldgDateTime());            // 11 LdgTime UCT (typo)
        durationIfPresent(excel, row, 11, item.flightDuration());     // 12 FlightDuration
        excel.intCell(row, 12, item.isSoloFlight() ? 1 : 0);         // 13 IsSoloFlight (1/0)
        if (item.startType() != null) {                              // 14 StartType (legacy int)
            excel.intCell(row, 13, item.startType());
        }
        stringIfPresent(excel, row, 14, item.startLocation());        // 15 StartLocation
        stringIfPresent(excel, row, 15, item.ldgLocation());          // 16 LdgLocation
        // Column 17 (index 16) intentionally skipped — no cell.
        stringIfPresent(excel, row, 17, item.flightComment());        // 18 FlightComment

        writeTowColumns(excel, row, item.towFlight());                // 19-30
    }

    private static void writeTowColumns(ExcelExportSupport excel, Row row, @Nullable TowFlightReportDataRecord tow) {
        if (tow == null) {
            return; // columns 19-30 left blank when the row has no tow
        }
        excel.stringCell(row, 18, idText(tow.towFlightId()));         // 19 TowFlight-FlightId
        stringIfPresent(excel, row, 19, tow.immatriculation());       // 20 TowFlight-Immatriculation
        stringIfPresent(excel, row, 20, tow.pilotName());             // 21 TowFlight-PilotName
        timeIfPresent(excel, row, 21, tow.startDateTime());           // 22 TowFlight-StartTime UTC
        timeIfPresent(excel, row, 22, tow.ldgDateTime());             // 23 TowFlight-LdgTime UTC
        durationIfPresent(excel, row, 23, tow.flightDuration());      // 24 TowFlight-FlightDuration
        stringIfPresent(excel, row, 24, tow.startLocation());         // 25 TowFlight-StartLocation
        stringIfPresent(excel, row, 25, tow.ldgLocation());           // 26 TowFlight-LdgLocation
        stringIfPresent(excel, row, 26, tow.flightCode());            // 27 TowFlight-FlightCode
        stringIfPresent(excel, row, 27, tow.flightTypeName());        // 28 TowFlight-FlightTypeName
        excel.intCell(row, 28, tow.airState());                       // 29 TowFlight-AirState (int)
        excel.intCell(row, 29, tow.processState());                   // 30 TowFlight-ProcessState (int)
    }

    private static void stringIfPresent(ExcelExportSupport excel, Row row, int column, @Nullable String value) {
        if (value != null) {
            excel.stringCell(row, column, value);
        }
    }

    private static void timeIfPresent(ExcelExportSupport excel, Row row, int column, @Nullable Instant value) {
        if (value != null) {
            excel.timeCell(row, column, utcWallClock(value));
        }
    }

    private static void durationIfPresent(ExcelExportSupport excel, Row row, int column, @Nullable Duration value) {
        if (value != null) {
            excel.durationCell(row, column, value.toSeconds());
        }
    }

    /** UTC wall-clock of an instant — the time cells render this verbatim (no TZ shift). */
    private static LocalDateTime utcWallClock(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String idText(ch.alpenflight.platform.id.FlightId id) {
        return id.value().toString();
    }
}
