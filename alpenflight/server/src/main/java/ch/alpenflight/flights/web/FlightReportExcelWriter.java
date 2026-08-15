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

final class FlightReportExcelWriter {

    static final String SHEET_NAME = "Flights";
    private static final int TITLE_FONT_SIZE = 20;
    private static final String TIMESTAMP_FORMAT = "dd.mm.yyyy HH:MM:ss";
    private static final int HEADER_ROW = 4;
    private static final int FIRST_DATA_ROW = 5;
    private static final int COLUMN_COUNT = 30;

    private static final String[] HEADERS = {
        "Flight ID",
        "FlightDate",
        "Immatriculation",
        "PilotName",
        "SecondCrewName",
        "AirState",
        "ProcessState",
        "FlightCode",
        "FlightTypeName",
        "StartTime UTC",
        "LdgTime UCT",
        "FlightDuration",
        "IsSoloFlight",
        "StartType",
        "StartLocation",
        "LdgLocation",
        "",
        "FlightComment",
        "TowFlight-FlightId",
        "TowFlight-Immatriculation",
        "TowFlight-PilotName",
        "TowFlight-StartTime UTC",
        "TowFlight-LdgTime UTC",
        "TowFlight-FlightDuration",
        "TowFlight-StartLocation",
        "TowFlight-LdgLocation",
        "TowFlight-FlightCode",
        "TowFlight-FlightTypeName",
        "TowFlight-AirState",
        "TowFlight-ProcessState",
    };

    private FlightReportExcelWriter() {}

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
        Row title = excel.dataRow(sheet, 0);
        excel.titleCell(title, 0, SHEET_NAME, TITLE_FONT_SIZE);
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
        excel.stringCell(row, 0, idText(item.flightId()));
        if (item.flightDate() != null) {
            row.createCell(1).setCellValue(item.flightDate().atStartOfDay());
        }
        stringIfPresent(excel, row, 2, item.immatriculation());
        stringIfPresent(excel, row, 3, item.pilotName());
        stringIfPresent(excel, row, 4, item.secondCrewName());
        excel.intCell(row, 5, item.airState());
        excel.intCell(row, 6, item.processState());
        stringIfPresent(excel, row, 7, item.flightCode());
        stringIfPresent(excel, row, 8, item.flightTypeName());
        timeIfPresent(excel, row, 9, item.startDateTime());
        timeIfPresent(excel, row, 10, item.ldgDateTime());
        durationIfPresent(excel, row, 11, item.flightDuration());
        excel.intCell(row, 12, item.isSoloFlight() ? 1 : 0);
        if (item.startType() != null) {
            excel.intCell(row, 13, item.startType());
        }
        stringIfPresent(excel, row, 14, item.startLocation());
        stringIfPresent(excel, row, 15, item.ldgLocation());
        stringIfPresent(excel, row, 17, item.flightComment());

        writeTowColumns(excel, row, item.towFlight());
    }

    private static void writeTowColumns(ExcelExportSupport excel, Row row, @Nullable TowFlightReportDataRecord tow) {
        if (tow == null) {
            return;
        }
        excel.stringCell(row, 18, idText(tow.towFlightId()));
        stringIfPresent(excel, row, 19, tow.immatriculation());
        stringIfPresent(excel, row, 20, tow.pilotName());
        timeIfPresent(excel, row, 21, tow.startDateTime());
        timeIfPresent(excel, row, 22, tow.ldgDateTime());
        durationIfPresent(excel, row, 23, tow.flightDuration());
        stringIfPresent(excel, row, 24, tow.startLocation());
        stringIfPresent(excel, row, 25, tow.ldgLocation());
        stringIfPresent(excel, row, 26, tow.flightCode());
        stringIfPresent(excel, row, 27, tow.flightTypeName());
        excel.intCell(row, 28, tow.airState());
        excel.intCell(row, 29, tow.processState());
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

    private static LocalDateTime utcWallClock(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String idText(ch.alpenflight.platform.id.FlightId id) {
        return id.value().toString();
    }
}
