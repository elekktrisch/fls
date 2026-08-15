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
    private static final int TITLE_ROW_INDEX = 0;
    private static final int GENERATED_AT_ROW_INDEX = 2;
    private static final int GENERATED_AT_LABEL_COLUMN_INDEX = 0;
    private static final int GENERATED_AT_VALUE_COLUMN_INDEX = 2;
    private static final int HEADER_ROW_INDEX = 4;
    private static final int FIRST_DATA_ROW_INDEX = 5;
    private static final int COLUMN_COUNT = 30;

    private static final String LDG_TIME_HEADER_KEEPING_THE_LEGACY_UCT_TYPO = "LdgTime UCT";
    private static final String NO_HEADER_LEGACY_LEAVES_THIS_COLUMN_EMPTY = "";

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
        LDG_TIME_HEADER_KEEPING_THE_LEGACY_UCT_TYPO,
        "FlightDuration",
        "IsSoloFlight",
        "StartType",
        "StartLocation",
        "LdgLocation",
        NO_HEADER_LEGACY_LEAVES_THIS_COLUMN_EMPTY,
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

    private static final class Col {
        static final int FLIGHT_ID = 0;
        static final int FLIGHT_DATE = 1;
        static final int IMMATRICULATION = 2;
        static final int PILOT_NAME = 3;
        static final int SECOND_CREW_NAME = 4;
        static final int AIR_STATE = 5;
        static final int PROCESS_STATE = 6;
        static final int FLIGHT_CODE = 7;
        static final int FLIGHT_TYPE_NAME = 8;
        static final int START_TIME = 9;
        static final int LDG_TIME = 10;
        static final int FLIGHT_DURATION = 11;
        static final int IS_SOLO_FLIGHT = 12;
        static final int START_TYPE = 13;
        static final int START_LOCATION = 14;
        static final int LDG_LOCATION = 15;
        static final int FLIGHT_COMMENT = 17;
        static final int TOW_FLIGHT_ID = 18;
        static final int TOW_IMMATRICULATION = 19;
        static final int TOW_PILOT_NAME = 20;
        static final int TOW_START_TIME = 21;
        static final int TOW_LDG_TIME = 22;
        static final int TOW_FLIGHT_DURATION = 23;
        static final int TOW_START_LOCATION = 24;
        static final int TOW_LDG_LOCATION = 25;
        static final int TOW_FLIGHT_CODE = 26;
        static final int TOW_FLIGHT_TYPE_NAME = 27;
        static final int TOW_AIR_STATE = 28;
        static final int TOW_PROCESS_STATE = 29;

        private Col() {}
    }

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
        Row title = excel.dataRow(sheet, TITLE_ROW_INDEX);
        excel.titleCell(title, 0, SHEET_NAME, TITLE_FONT_SIZE);
        Row meta = excel.dataRow(sheet, GENERATED_AT_ROW_INDEX);
        excel.stringCell(meta, GENERATED_AT_LABEL_COLUMN_INDEX, "Excel Erstellt:");
        excel.dateCell(meta, GENERATED_AT_VALUE_COLUMN_INDEX, utcWallClock(generatedAt), TIMESTAMP_FORMAT);
    }

    private static void writeHeader(ExcelExportSupport excel, SXSSFSheet sheet) {
        excel.headerRow(sheet, HEADER_ROW_INDEX, HEADERS);
    }

    private static void writeDataRows(ExcelExportSupport excel, SXSSFSheet sheet, FlightReportResult result) {
        int rowIndex = FIRST_DATA_ROW_INDEX;
        for (FlightReportDataRecord item : result.items()) {
            writeDataRow(excel, excel.dataRow(sheet, rowIndex++), item);
        }
    }

    private static void writeDataRow(ExcelExportSupport excel, Row row, FlightReportDataRecord item) {
        excel.stringCell(row, Col.FLIGHT_ID, idText(item.flightId()));
        if (item.flightDate() != null) {
            row.createCell(Col.FLIGHT_DATE).setCellValue(item.flightDate().atStartOfDay());
        }
        stringIfPresent(excel, row, Col.IMMATRICULATION, item.immatriculation());
        stringIfPresent(excel, row, Col.PILOT_NAME, item.pilotName());
        stringIfPresent(excel, row, Col.SECOND_CREW_NAME, item.secondCrewName());
        excel.intCell(row, Col.AIR_STATE, item.airState());
        excel.intCell(row, Col.PROCESS_STATE, item.processState());
        stringIfPresent(excel, row, Col.FLIGHT_CODE, item.flightCode());
        stringIfPresent(excel, row, Col.FLIGHT_TYPE_NAME, item.flightTypeName());
        timeIfPresent(excel, row, Col.START_TIME, item.startDateTime());
        timeIfPresent(excel, row, Col.LDG_TIME, item.ldgDateTime());
        durationIfPresent(excel, row, Col.FLIGHT_DURATION, item.flightDuration());
        excel.intCell(row, Col.IS_SOLO_FLIGHT, item.isSoloFlight() ? 1 : 0);
        if (item.startType() != null) {
            excel.intCell(row, Col.START_TYPE, item.startType());
        }
        stringIfPresent(excel, row, Col.START_LOCATION, item.startLocation());
        stringIfPresent(excel, row, Col.LDG_LOCATION, item.ldgLocation());
        stringIfPresent(excel, row, Col.FLIGHT_COMMENT, item.flightComment());

        writeTowColumns(excel, row, item.towFlight());
    }

    private static void writeTowColumns(ExcelExportSupport excel, Row row, @Nullable TowFlightReportDataRecord tow) {
        if (tow == null) {
            return;
        }
        excel.stringCell(row, Col.TOW_FLIGHT_ID, idText(tow.towFlightId()));
        stringIfPresent(excel, row, Col.TOW_IMMATRICULATION, tow.immatriculation());
        stringIfPresent(excel, row, Col.TOW_PILOT_NAME, tow.pilotName());
        timeIfPresent(excel, row, Col.TOW_START_TIME, tow.startDateTime());
        timeIfPresent(excel, row, Col.TOW_LDG_TIME, tow.ldgDateTime());
        durationIfPresent(excel, row, Col.TOW_FLIGHT_DURATION, tow.flightDuration());
        stringIfPresent(excel, row, Col.TOW_START_LOCATION, tow.startLocation());
        stringIfPresent(excel, row, Col.TOW_LDG_LOCATION, tow.ldgLocation());
        stringIfPresent(excel, row, Col.TOW_FLIGHT_CODE, tow.flightCode());
        stringIfPresent(excel, row, Col.TOW_FLIGHT_TYPE_NAME, tow.flightTypeName());
        excel.intCell(row, Col.TOW_AIR_STATE, tow.airState());
        excel.intCell(row, Col.TOW_PROCESS_STATE, tow.processState());
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
