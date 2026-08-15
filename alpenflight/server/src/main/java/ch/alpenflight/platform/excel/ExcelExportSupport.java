package ch.alpenflight.platform.excel;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

public final class ExcelExportSupport {

    public static final int DEFAULT_ROW_WINDOW = 100;

    private final SXSSFWorkbook workbook;
    private final CreationHelper creationHelper;
    private final Map<String, CellStyle> formatStyles = new HashMap<>();
    private final Map<Short, CellStyle> titleStyles = new HashMap<>();

    private ExcelExportSupport(SXSSFWorkbook workbook) {
        this.workbook = workbook;
        this.creationHelper = workbook.getCreationHelper();
    }

    public static ExcelExportSupport streamingWorkbook() {
        return new ExcelExportSupport(new SXSSFWorkbook(DEFAULT_ROW_WINDOW));
    }

    public SXSSFWorkbook workbook() {
        return workbook;
    }

    public Row headerRow(Sheet sheet, int rowIndex, String... headers) {
        Row row = sheet.createRow(rowIndex);
        for (int c = 0; c < headers.length; c++) {
            row.createCell(c).setCellValue(headers[c]);
        }
        return row;
    }

    public Row dataRow(Sheet sheet, int rowIndex) {
        return sheet.createRow(rowIndex);
    }

    public Cell stringCell(Row row, int column, String value) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        return cell;
    }

    public Cell intCell(Row row, int column, long value) {
        Cell cell = row.createCell(column);
        cell.setCellValue((double) value);
        return cell;
    }

    public Cell dateCell(Row row, int column, LocalDateTime value, String formatString) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(formatStyle(formatString));
        return cell;
    }

    public Cell timeCell(Row row, int column, LocalDateTime value) {
        return dateCell(row, column, value, "HH:MM");
    }

    public Cell durationCell(Row row, int column, long durationSeconds) {
        Cell cell = row.createCell(column);
        cell.setCellValue(durationSeconds / 86_400.0d);
        cell.setCellStyle(formatStyle("[H]:MM"));
        return cell;
    }

    public Cell formattedCell(Row row, int column, double value, String formatString) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(formatStyle(formatString));
        return cell;
    }

    public Cell titleCell(Row row, int column, String value, int fontSizeInPoints) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(titleStyle((short) fontSizeInPoints));
        return cell;
    }

    public void autoSize(Sheet sheet, int columnCount) {
        if (sheet instanceof SXSSFSheet sxssf) {
            sxssf.trackAllColumnsForAutoSizing();
        }
        for (int c = 0; c < columnCount; c++) {
            sheet.autoSizeColumn(c);
        }
    }

    public void trackColumnsForAutoSizing(SXSSFSheet sheet) {
        sheet.trackAllColumnsForAutoSizing();
    }

    private CellStyle formatStyle(String formatString) {
        return formatStyles.computeIfAbsent(formatString, fs -> {
            CellStyle style = workbook.createCellStyle();
            style.setDataFormat(creationHelper.createDataFormat().getFormat(fs));
            return style;
        });
    }

    private CellStyle titleStyle(short fontSizeInPoints) {
        return titleStyles.computeIfAbsent(fontSizeInPoints, size -> {
            Font font = workbook.createFont();
            font.setFontHeightInPoints(size);
            CellStyle style = workbook.createCellStyle();
            style.setFont(font);
            return style;
        });
    }
}
