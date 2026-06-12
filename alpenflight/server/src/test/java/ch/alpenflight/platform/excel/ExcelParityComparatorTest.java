package ch.alpenflight.platform.excel;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/**
 * Proves the {@link ExcelParityComparator} is STRICT on parity-load-bearing
 * dimensions (value, type, number-format) and TOLERANT of cosmetic style — i.e. the
 * harness fails for the right reasons and not the wrong ones. Without this, a green
 * {@code FlightReportExcelParityIT} could be a false positive from an over-lenient
 * comparator.
 */
class ExcelParityComparatorTest {

    @Test
    void identicalWorkbooks_areEqual() throws Exception {
        try (XSSFWorkbook a = wb();
                XSSFWorkbook b = wb()) {
            numericCell(a, 0, 0, 1.5, "HH:MM");
            numericCell(b, 0, 0, 1.5, "HH:MM");
            assertThat(ExcelParityComparator.compare(a, b).isEqual())
                    .as("identical workbooks").isTrue();
        }
    }

    @Test
    void differentNumberFormat_isAMismatch() throws Exception {
        try (XSSFWorkbook a = wb();
                XSSFWorkbook b = wb()) {
            numericCell(a, 0, 0, 1.5, "HH:MM");
            numericCell(b, 0, 0, 1.5, "[H]:MM");
            ExcelParityComparator.Diff diff = ExcelParityComparator.compare(a, b);
            assertThat(diff.isEqual()).isFalse();
            assertThat(diff.describe()).contains("number-format").contains("HH:MM").contains("[H]:MM");
        }
    }

    @Test
    void differentValue_isAMismatch() throws Exception {
        try (XSSFWorkbook a = wb();
                XSSFWorkbook b = wb()) {
            cell(a, 0, 0).setCellValue(1.0);
            cell(b, 0, 0).setCellValue(2.0);
            ExcelParityComparator.Diff diff = ExcelParityComparator.compare(a, b);
            assertThat(diff.isEqual()).isFalse();
            assertThat(diff.describe()).contains("numeric value");
        }
    }

    @Test
    void differentType_isAMismatch() throws Exception {
        try (XSSFWorkbook a = wb();
                XSSFWorkbook b = wb()) {
            cell(a, 0, 0).setCellValue("2");
            cell(b, 0, 0).setCellValue(2.0);
            ExcelParityComparator.Diff diff = ExcelParityComparator.compare(a, b);
            assertThat(diff.isEqual()).isFalse();
            assertThat(diff.describe()).contains("type");
        }
    }

    @Test
    void cosmeticStyleDifference_isTolerated() throws Exception {
        try (XSSFWorkbook a = wb();
                XSSFWorkbook b = wb()) {
            cell(a, 0, 0).setCellValue("Flights"); // plain
            // bold, big font, colored fill, wide column — all cosmetic.
            Cell styled = cell(b, 0, 0);
            styled.setCellValue("Flights");
            Font font = b.createFont();
            font.setBold(true);
            font.setFontHeightInPoints((short) 20);
            CellStyle style = b.createCellStyle();
            style.setFont(font);
            style.setFillForegroundColor(IndexedColors.YELLOW.getIndex());
            styled.setCellStyle(style);
            b.getSheetAt(0).setColumnWidth(0, 9000);

            assertThat(ExcelParityComparator.compare(a, b).isEqual())
                    .as("cosmetic font/fill/width differences tolerated").isTrue();
        }
    }

    @Test
    void cellPresentInOnlyOne_isAMismatch() throws Exception {
        try (XSSFWorkbook a = wb();
                XSSFWorkbook b = wb()) {
            cell(a, 0, 0).setCellValue("x");
            b.getSheetAt(0).createRow(0); // empty row, no cell
            ExcelParityComparator.Diff diff = ExcelParityComparator.compare(a, b);
            assertThat(diff.isEqual()).isFalse();
            assertThat(diff.describe()).contains("present in only one");
        }
    }

    @Test
    void differentSheetName_isAMismatch() throws Exception {
        try (XSSFWorkbook a = new XSSFWorkbook();
                XSSFWorkbook b = new XSSFWorkbook()) {
            a.createSheet("Flights");
            b.createSheet("Tabelle1");
            ExcelParityComparator.Diff diff = ExcelParityComparator.compare(a, b);
            assertThat(diff.isEqual()).isFalse();
            assertThat(diff.describe()).contains("name");
        }
    }

    // --- helpers -------------------------------------------------------------

    private static XSSFWorkbook wb() {
        XSSFWorkbook wb = new XSSFWorkbook();
        wb.createSheet("Flights");
        return wb;
    }

    private static Cell cell(XSSFWorkbook wb, int r, int c) {
        Sheet sheet = wb.getSheetAt(0);
        Row row = sheet.getRow(r);
        if (row == null) {
            row = sheet.createRow(r);
        }
        return row.createCell(c);
    }

    private static void numericCell(XSSFWorkbook wb, int r, int c, double value, String fmt) {
        Cell cell = cell(wb, r, c);
        cell.setCellValue(value);
        CellStyle style = wb.createCellStyle();
        style.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat(fmt));
        cell.setCellStyle(style);
    }
}
