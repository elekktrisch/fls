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

final class FlightReportGoldenFixture {

    static final String RESOURCE_PATH = "/excel-parity/flight-reports-legacy-golden.xlsx";

    private static final String TIMESTAMP_FORMAT = "dd.mm.yyyy HH:MM:ss";
    private static final String TIME_FORMAT = "HH:MM";
    private static final String DURATION_FORMAT = "[H]:MM";

    private static final int LDG_LOCATION_COLUMN = 15;
    private static final int BLANK_LEGACY_GAP_COLUMN = LDG_LOCATION_COLUMN + 1;
    private static final int FLIGHT_COMMENT_COLUMN = BLANK_LEGACY_GAP_COLUMN + 1;

    private FlightReportGoldenFixture() {}

    // RENAME: write -> writeContractByHandNotViaTheProductionWriter
    static void write(OutputStream out) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            CreationHelper helper = wb.getCreationHelper();
            CellStyle title = titleStyle(wb, 20);
            CellStyle ts = formatStyle(wb, helper, TIMESTAMP_FORMAT);
            CellStyle time = formatStyle(wb, helper, TIME_FORMAT);
            CellStyle duration = formatStyle(wb, helper, DURATION_FORMAT);

            Sheet sheet = wb.createSheet(FlightReportExcelWriter.SHEET_NAME);

            styled(sheet.createRow(0).createCell(0), FlightReportExcelWriter.SHEET_NAME, title);
            Row meta = sheet.createRow(2);
            meta.createCell(0).setCellValue("Excel Erstellt:");
            Cell c3 = meta.createCell(2);
            c3.setCellValue(FlightReportGoldenDataset.GENERATED_AT_UTC);
            c3.setCellStyle(ts);

            Row header = sheet.createRow(4);
            for (int c = 0; c < FlightReportGoldenDataset.HEADERS.length; c++) {
                header.createCell(c).setCellValue(FlightReportGoldenDataset.HEADERS[c]);
            }

            int rowIndex = 5;
            for (FlightReportGoldenDataset.GoldenRow gr : FlightReportGoldenDataset.rows()) {
                writeRow(sheet.createRow(rowIndex++), gr, time, duration);
            }

            wb.write(out);
        }
    }

    private static void writeRow(
            Row row, FlightReportGoldenDataset.GoldenRow gr, CellStyle time, CellStyle duration) {
        row.createCell(0).setCellValue(gr.flightId());
        row.createCell(1).setCellValue(gr.flightDate().atStartOfDay());
        row.createCell(2).setCellValue(gr.immatriculation());
        row.createCell(3).setCellValue(gr.pilotName());
        if (gr.secondCrewName() != null) {
            row.createCell(4).setCellValue(gr.secondCrewName());
        }
        row.createCell(5).setCellValue(gr.airState());
        row.createCell(6).setCellValue(gr.processState());
        row.createCell(7).setCellValue(gr.flightCode());
        row.createCell(8).setCellValue(gr.flightTypeName());
        timeCell(row, 9, gr.startTime(), time);
        timeCell(row, 10, gr.ldgTime(), time);
        durationCell(row, 11, gr.durationSeconds(), duration);
        row.createCell(12).setCellValue(gr.isSoloFlight() ? 1 : 0);
        row.createCell(13).setCellValue(gr.startType());
        row.createCell(14).setCellValue(gr.startLocation());
        row.createCell(LDG_LOCATION_COLUMN).setCellValue(gr.ldgLocation());
        row.createCell(FLIGHT_COMMENT_COLUMN).setCellValue(gr.flightComment());

        FlightReportGoldenDataset.GoldenTow tow = gr.tow();
        if (tow != null) {
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
