package ch.alpenflight.platform.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class ExcelExportSupportTest {

    @Test
    void streamingWorkbook_usesDefaultRowWindow() {
        ExcelExportSupport support = ExcelExportSupport.streamingWorkbook();

        SXSSFWorkbook wb = support.workbook();

        assertThat(wb.getRandomAccessWindowSize()).isEqualTo(ExcelExportSupport.DEFAULT_ROW_WINDOW);
        dispose(support);
    }

    @Test
    void headerRow_writesStringHeadersFromColumnZero() throws IOException {
        Cell[] back = roundTripRow(support -> {
            Sheet sheet = support.workbook().createSheet("Flights");
            support.headerRow(sheet, 0, "FlightDate", "PilotName", "LdgTime UCT");
        }, 0, 3);

        assertThat(back[0].getStringCellValue()).isEqualTo("FlightDate");
        assertThat(back[1].getStringCellValue()).isEqualTo("PilotName");
        assertThat(back[2].getStringCellValue()).isEqualTo("LdgTime UCT");
    }

    @Test
    void dataRow_isEmptyRowReadyForTypedCells() throws IOException {
        Cell[] back = roundTripRow(support -> {
            Sheet sheet = support.workbook().createSheet("Flights");
            Row row = support.dataRow(sheet, 5);
            support.stringCell(row, 0, "HB-1234");
        }, 5, 1);

        assertThat(back[0].getStringCellValue()).isEqualTo("HB-1234");
    }

    @Test
    void stringCell_writesStringValue() throws IOException {
        Cell[] back = roundTripRow(support -> {
            Sheet sheet = support.workbook().createSheet("Flights");
            Row row = support.dataRow(sheet, 0);
            support.stringCell(row, 0, "Glider");
        }, 0, 1);

        assertThat(back[0].getStringCellValue()).isEqualTo("Glider");
    }

    @Test
    void intCell_writesRawIntegerValue() throws IOException {
        Cell[] back = roundTripRow(support -> {
            Sheet sheet = support.workbook().createSheet("Flights");
            Row row = support.dataRow(sheet, 0);
            support.intCell(row, 0, 1L);
        }, 0, 1);

        assertThat(back[0].getNumericCellValue()).isEqualTo(1.0d);
    }

    @Test
    void dateCell_storesDateValue_andTimestampFormat() throws IOException {
        LocalDateTime ts = LocalDateTime.of(2026, 6, 9, 14, 5, 30);
        Cell[] back = roundTripRow(support -> {
            Sheet sheet = support.workbook().createSheet("Flights");
            Row row = support.dataRow(sheet, 2);
            support.dateCell(row, 2, ts, "dd.mm.yyyy HH:MM:ss");
        }, 2, 3);

        assertThat(back[2].getLocalDateTimeCellValue()).isEqualTo(ts);
        assertThat(back[2].getCellStyle().getDataFormatString()).isEqualTo("dd.mm.yyyy HH:MM:ss");
    }

    @Test
    void timeCell_usesHourMinuteFormat() throws IOException {
        LocalDateTime time = LocalDateTime.of(2026, 6, 9, 9, 45, 0);
        Cell[] back = roundTripRow(support -> {
            Sheet sheet = support.workbook().createSheet("Flights");
            Row row = support.dataRow(sheet, 0);
            support.timeCell(row, 0, time);
        }, 0, 1);

        assertThat(back[0].getLocalDateTimeCellValue()).isEqualTo(time);
        assertThat(back[0].getCellStyle().getDataFormatString()).isEqualTo("HH:MM");
    }

    @Test
    void durationCell_usesElapsedHoursFormat_andFractionOfDayValue() throws IOException {
        long seconds = 3 * 3600 + 30 * 60;
        Cell[] back = roundTripRow(support -> {
            Sheet sheet = support.workbook().createSheet("Flights");
            Row row = support.dataRow(sheet, 0);
            support.durationCell(row, 0, seconds);
        }, 0, 1);

        assertThat(back[0].getNumericCellValue()).isCloseTo(seconds / 86_400.0d, within(1e-9));
        assertThat(back[0].getCellStyle().getDataFormatString()).isEqualTo("[H]:MM");
    }

    @Test
    void formattedCell_appliesArbitraryNumberFormat() throws IOException {
        Cell[] back = roundTripRow(support -> {
            Sheet sheet = support.workbook().createSheet("Flights");
            Row row = support.dataRow(sheet, 0);
            support.formattedCell(row, 0, 12.5d, "0.00");
        }, 0, 1);

        assertThat(back[0].getNumericCellValue()).isEqualTo(12.5d);
        assertThat(back[0].getCellStyle().getDataFormatString()).isEqualTo("0.00");
    }

    @Test
    void titleCell_appliesFontPointSize() throws IOException {
        XSSFWorkbook read = roundTrip(support -> {
            Sheet sheet = support.workbook().createSheet("Flights");
            Row row = support.dataRow(sheet, 0);
            support.titleCell(row, 0, "Flights", 20);
        });
        Cell cell = read.getSheet("Flights").getRow(0).getCell(0);

        assertThat(cell.getStringCellValue()).isEqualTo("Flights");
        int fontIndex = cell.getCellStyle().getFontIndex();
        assertThat(read.getFontAt(fontIndex).getFontHeightInPoints()).isEqualTo((short) 20);
        read.close();
    }

    @Test
    void formatStyle_isReusedAcrossCellsWithTheSameFormat() throws IOException {
        XSSFWorkbook read = roundTrip(support -> {
            Sheet sheet = support.workbook().createSheet("Flights");
            Row row = support.dataRow(sheet, 0);
            support.timeCell(row, 0, LocalDateTime.of(2026, 6, 9, 9, 0));
            support.timeCell(row, 1, LocalDateTime.of(2026, 6, 9, 10, 0));
        });
        Row row = read.getSheet("Flights").getRow(0);

        assertThat(row.getCell(0).getCellStyle().getIndex())
                .as("the same format string reuses one cached style — guards the 64k cell-style cap")
                .isEqualTo(row.getCell(1).getCellStyle().getIndex());
        read.close();
    }

    @Test
    void autoSize_tracksColumnsAndSizesWithoutThrowing() throws IOException {
        ExcelExportSupport support = ExcelExportSupport.streamingWorkbook();
        SXSSFSheet sheet = support.workbook().createSheet("Flights");
        support.trackColumnsForAutoSizing(sheet);
        Row row = support.dataRow(sheet, 0);
        support.stringCell(row, 0, "a-wide-immatriculation-value");
        support.stringCell(row, 1, "x");

        support.autoSize(sheet, 2);

        assertThat(sheet.getColumnWidth(0)).isPositive();
        assertThat(sheet.getColumnWidth(0)).isGreaterThan(sheet.getColumnWidth(1));
        dispose(support);
    }


    private interface SheetWriter {
        void write(ExcelExportSupport support);
    }

    private static XSSFWorkbook roundTrip(SheetWriter writer) throws IOException {
        ExcelExportSupport support = ExcelExportSupport.streamingWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            writer.write(support);
            support.workbook().write(out);
        } finally {
            dispose(support);
        }
        return new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()));
    }

    private static Cell[] roundTripRow(SheetWriter writer, int rowIndex, int count) throws IOException {
        try (XSSFWorkbook read = roundTrip(writer)) {
            Row row = read.getSheet("Flights").getRow(rowIndex);
            Cell[] cells = new Cell[count];
            for (int c = 0; c < count; c++) {
                cells[c] = row.getCell(c);
            }
            return cells;
        }
    }

    private static void dispose(ExcelExportSupport support) {
        support.workbook().dispose();
        try {
            support.workbook().close();
        } catch (IOException ignored) {
        }
    }
}
