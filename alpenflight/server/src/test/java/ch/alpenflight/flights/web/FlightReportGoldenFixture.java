package ch.alpenflight.flights.web;

import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDateTime;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Builds the FlightReports Excel <b>golden fixture</b> — the cell-parity contract
 * the S-096 harness (J-7 T-08) validates {@link FlightReportExcelWriter} against.
 *
 * <p><b>PROVENANCE — read this before treating a green harness as legacy-byte-match.</b>
 * This fixture is derived from the <b>S-093 export inventory + the legacy behavior
 * oracle</b> (the J-7 "Parity decisions" note + {@code FlightReportService.cs:743-859}),
 * <b>NOT</b> from a live legacy export. The legacy {@code flsserver} stack (Mono +
 * MSSQL + EPPlus) does <b>not run on this Alpine/musl dev box</b>, so a live legacy
 * {@code .xlsx} cannot be generated here. Mirror of T-01's deferred legacy
 * screenshots ({@code e2e/legacy-reference/reporting/PENDING.md}): the harness proves
 * OUR export matches the <em>documented</em> contract; the live-legacy byte-match is a
 * <b>fanout-gate</b> concern — a live-legacy fixture swaps in here when the fan-out
 * workflow brings up the legacy stack (same lineage as the deferred legacy shots).
 *
 * <p>This generator is the SINGLE source of the fixture: it builds the workbook by
 * hand from the contract (independent of the production {@code FlightReportExcelWriter}
 * — if both shared code, the harness would be tautological). The committed fixture
 * {@code src/test/resources/excel-parity/flight-reports-legacy-golden.xlsx} is a
 * checked-in snapshot of this generator's output; {@link FlightReportGoldenFixtureTest}
 * asserts the committed bytes still match this generator (so the fixture cannot drift
 * silently), and {@link FlightReportExcelParityIT} asserts our writer reproduces it
 * cell-for-cell over the matching dataset ({@link FlightReportGoldenDataset}).
 *
 * <p>Encoded contract (legacy layout, oracle §5):
 * <ul>
 *   <li>Sheet {@code Flights}; A1 {@code Flights} (font 20); row 2 blank; A3
 *       {@code Excel Erstellt:}; C3 timestamp {@code dd.mm.yyyy HH:MM:ss}.</li>
 *   <li>Header row 5 (30 cols incl. blank col 17 + the preserved typo
 *       {@code LdgTime UCT} on col 11); data from row 6.</li>
 *   <li>Two data rows from {@link FlightReportGoldenDataset}: a plain glider row
 *       and an aerotow glider carrying the nested TowFlight block (cols 19-30).</li>
 *   <li>Time cells {@code HH:MM}; duration {@code [H]:MM}; IsSoloFlight 0/1;
 *       StartType / AirState / ProcessState raw ints.</li>
 * </ul>
 */
final class FlightReportGoldenFixture {

    /** Classpath location of the committed golden fixture. */
    static final String RESOURCE_PATH = "/excel-parity/flight-reports-legacy-golden.xlsx";

    private static final String TIMESTAMP_FORMAT = "dd.mm.yyyy HH:MM:ss";
    private static final String TIME_FORMAT = "HH:MM";
    private static final String DURATION_FORMAT = "[H]:MM";

    private FlightReportGoldenFixture() {}

    /**
     * Writes the golden fixture to {@code out}, using {@link FlightReportGoldenDataset}
     * for the data rows. Built by hand from the contract — deliberately NOT via the
     * production writer.
     */
    static void write(OutputStream out) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            CreationHelper helper = wb.getCreationHelper();
            CellStyle title = titleStyle(wb, 20);
            CellStyle ts = formatStyle(wb, helper, TIMESTAMP_FORMAT);
            CellStyle time = formatStyle(wb, helper, TIME_FORMAT);
            CellStyle duration = formatStyle(wb, helper, DURATION_FORMAT);

            Sheet sheet = wb.createSheet(FlightReportExcelWriter.SHEET_NAME);

            // A1 "Flights" (font 20); row 2 blank; A3 "Excel Erstellt:" + C3 timestamp.
            styled(sheet.createRow(0).createCell(0), FlightReportExcelWriter.SHEET_NAME, title);
            Row meta = sheet.createRow(2);
            meta.createCell(0).setCellValue("Excel Erstellt:");
            Cell c3 = meta.createCell(2);
            c3.setCellValue(FlightReportGoldenDataset.GENERATED_AT_UTC);
            c3.setCellStyle(ts);

            // Header row 5 (index 4).
            Row header = sheet.createRow(4);
            for (int c = 0; c < FlightReportGoldenDataset.HEADERS.length; c++) {
                header.createCell(c).setCellValue(FlightReportGoldenDataset.HEADERS[c]);
            }

            // Data rows from row 6 (index 5).
            int rowIndex = 5;
            for (FlightReportGoldenDataset.GoldenRow gr : FlightReportGoldenDataset.rows()) {
                writeRow(sheet.createRow(rowIndex++), gr, time, duration);
            }

            wb.write(out);
        }
    }

    private static void writeRow(
            Row row, FlightReportGoldenDataset.GoldenRow gr, CellStyle time, CellStyle duration) {
        row.createCell(0).setCellValue(gr.flightId());               // 1 Flight ID
        row.createCell(1).setCellValue(gr.flightDate().atStartOfDay()); // 2 FlightDate (no format)
        row.createCell(2).setCellValue(gr.immatriculation());        // 3 Immatriculation
        row.createCell(3).setCellValue(gr.pilotName());              // 4 PilotName
        if (gr.secondCrewName() != null) {                          // 5 SecondCrewName
            row.createCell(4).setCellValue(gr.secondCrewName());
        }
        row.createCell(5).setCellValue(gr.airState());              // 6 AirState
        row.createCell(6).setCellValue(gr.processState());          // 7 ProcessState
        row.createCell(7).setCellValue(gr.flightCode());            // 8 FlightCode
        row.createCell(8).setCellValue(gr.flightTypeName());        // 9 FlightTypeName
        timeCell(row, 9, gr.startTime(), time);                     // 10 StartTime UTC
        timeCell(row, 10, gr.ldgTime(), time);                      // 11 LdgTime UCT (typo)
        durationCell(row, 11, gr.durationSeconds(), duration);      // 12 FlightDuration
        row.createCell(12).setCellValue(gr.isSoloFlight() ? 1 : 0); // 13 IsSoloFlight
        row.createCell(13).setCellValue(gr.startType());            // 14 StartType (legacy int)
        row.createCell(14).setCellValue(gr.startLocation());        // 15 StartLocation
        row.createCell(15).setCellValue(gr.ldgLocation());          // 16 LdgLocation
        // Column 17 (index 16) intentionally blank — no cell.
        row.createCell(17).setCellValue(gr.flightComment());        // 18 FlightComment

        FlightReportGoldenDataset.GoldenTow tow = gr.tow();
        if (tow != null) {                                          // 19-30 nested tow
            row.createCell(18).setCellValue(tow.towFlightId());
            row.createCell(19).setCellValue(tow.immatriculation());
            row.createCell(20).setCellValue(tow.pilotName());
            timeCell(row, 21, tow.startTime(), time);
            timeCell(row, 22, tow.ldgTime(), time);
            durationCell(row, 23, tow.durationSeconds(), duration);
            row.createCell(24).setCellValue(tow.startLocation());
            row.createCell(25).setCellValue(tow.ldgLocation());
            row.createCell(26).setCellValue(tow.flightCode());
            row.createCell(27).setCellValue(tow.flightTypeName());
            row.createCell(28).setCellValue(tow.airState());
            row.createCell(29).setCellValue(tow.processState());
        }
    }

    private static void timeCell(Row row, int col, LocalDateTime value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private static void durationCell(Row row, int col, long seconds, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(seconds / 86_400.0d);
        cell.setCellStyle(style);
    }

    private static void styled(Cell cell, String value, CellStyle style) {
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private static CellStyle formatStyle(XSSFWorkbook wb, CreationHelper helper, String fmt) {
        CellStyle style = wb.createCellStyle();
        style.setDataFormat(helper.createDataFormat().getFormat(fmt));
        return style;
    }

    private static CellStyle titleStyle(XSSFWorkbook wb, int fontSize) {
        Font font = wb.createFont();
        font.setFontHeightInPoints((short) fontSize);
        CellStyle style = wb.createCellStyle();
        style.setFont(font);
        return style;
    }
}
