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

/**
 * Reusable Apache POI helper for synchronous Excel (.xlsx) exports, backed by a
 * {@link SXSSFWorkbook streaming workbook} (S-094). Bound to one workbook so it
 * can cache {@link CellStyle styles} per number-format string — POI caps a
 * workbook at ~64k styles, so a fresh style per cell would blow the limit on a
 * large export.
 *
 * <p>Scope: exactly what the flight-reports export (J-7 T-07) needs against the
 * legacy Excel oracle — string / int / date / time-of-day / duration cells with
 * a parity-load-bearing <em>number-format string</em> (the format is what the
 * parity harness asserts, not the rendered text), a font-sized title cell (A1
 * "Flights" font 20), and SXSSF-correct column autosize. Currency / locale cells
 * are a deferred extension for J-10's statistics exports (not built here, per
 * vertical-slices-first).
 *
 * <p>Number-format strings used by the flight-reports oracle:
 * <ul>
 *   <li>{@code dd.mm.yyyy HH:MM:ss} — the A3/C3 metadata "Excel Erstellt" timestamp</li>
 *   <li>{@code HH:MM} — time-of-day cells (UTC wall-clock, no TZ shift)</li>
 *   <li>{@code [H]:MM} — durations (elapsed hours, may exceed 24)</li>
 * </ul>
 *
 * <p>Default styling beyond the title font size is intentionally minimal:
 * header bold/borders are cosmetic and harness-ignored (J-7 parity decisions),
 * so this helper does not over-engineer them. The number-format strings ARE
 * parity-load-bearing and are first-class here.
 *
 * <p>Not thread-safe — one instance per export, single-threaded, like the
 * workbook it wraps.
 */
public final class ExcelExportSupport {

    /** Default SXSSF in-memory row window (S-094). Rows beyond it flush to disk. */
    public static final int DEFAULT_ROW_WINDOW = 100;

    private final SXSSFWorkbook workbook;
    private final CreationHelper creationHelper;
    /** One style per number-format string, reused across cells (64k-style cap). */
    private final Map<String, CellStyle> formatStyles = new HashMap<>();
    /** One style per font point-size, reused across title cells. */
    private final Map<Short, CellStyle> titleStyles = new HashMap<>();

    private ExcelExportSupport(SXSSFWorkbook workbook) {
        this.workbook = workbook;
        this.creationHelper = workbook.getCreationHelper();
    }

    /**
     * Creates a streaming workbook with the default {@value #DEFAULT_ROW_WINDOW}-row
     * window and returns the helper bound to it. The caller owns the workbook
     * lifecycle: {@code write(out)} then {@code close()} (close also disposes the
     * SXSSF temp files). Reachable as {@link #workbook()}.
     */
    public static ExcelExportSupport streamingWorkbook() {
        return new ExcelExportSupport(new SXSSFWorkbook(DEFAULT_ROW_WINDOW));
    }

    /** The underlying streaming workbook (for {@code write}/{@code close}/{@code createSheet}). */
    public SXSSFWorkbook workbook() {
        return workbook;
    }

    /**
     * Writes a row of string header cells starting at column 0 of {@code rowIndex}.
     * Header styling beyond the values is cosmetic (harness-ignored), so this
     * writes plain string cells.
     *
     * @return the created row, for any follow-up cell writes
     */
    public Row headerRow(Sheet sheet, int rowIndex, String... headers) {
        Row row = sheet.createRow(rowIndex);
        for (int c = 0; c < headers.length; c++) {
            row.createCell(c).setCellValue(headers[c]);
        }
        return row;
    }

    /**
     * Creates an empty data row at {@code rowIndex}; callers fill it with the
     * typed {@code *Cell} helpers (column order is the caller's parity contract).
     */
    public Row dataRow(Sheet sheet, int rowIndex) {
        return sheet.createRow(rowIndex);
    }

    /** Writes a plain string cell. */
    public Cell stringCell(Row row, int column, String value) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        return cell;
    }

    /** Writes a raw integer cell (AirState / ProcessState / StartType / IsSoloFlight 0/1). */
    public Cell intCell(Row row, int column, long value) {
        Cell cell = row.createCell(column);
        // Excel numbers are IEEE-754 doubles; the int range we export
        // (small enum codes / 0-1 flags) is exactly representable.
        cell.setCellValue((double) value);
        return cell;
    }

    /**
     * Writes a date/time cell carrying a date value and the given number-format
     * string (e.g. {@code dd.mm.yyyy HH:MM:ss} for the metadata timestamp). The
     * stored value is the date; the format string is what the parity harness
     * asserts.
     */
    public Cell dateCell(Row row, int column, LocalDateTime value, String formatString) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(formatStyle(formatString));
        return cell;
    }

    /**
     * Writes a time-of-day cell ({@code HH:MM}) from a {@link LocalDateTime}
     * carrying the UTC wall-clock time (no TZ shift — the value is stored as-is).
     */
    public Cell timeCell(Row row, int column, LocalDateTime value) {
        return dateCell(row, column, value, "HH:MM");
    }

    /**
     * Writes a duration cell ({@code [H]:MM}) from a whole number of seconds. The
     * value is stored as a fraction of a day (POI's time-serial convention) so
     * the {@code [H]:MM} elapsed-time format renders hours that may exceed 24.
     */
    public Cell durationCell(Row row, int column, long durationSeconds) {
        Cell cell = row.createCell(column);
        cell.setCellValue(durationSeconds / 86_400.0d);
        cell.setCellStyle(formatStyle("[H]:MM"));
        return cell;
    }

    /**
     * Writes a numeric cell with an arbitrary number-format string — the generic
     * escape hatch behind {@link #timeCell}/{@link #durationCell} and the seam
     * J-10 extends for currency/locale formats.
     */
    public Cell formattedCell(Row row, int column, double value, String formatString) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(formatStyle(formatString));
        return cell;
    }

    /**
     * Writes a title cell (e.g. A1 "Flights") with the given font point-size.
     * Other title styling is cosmetic and not applied.
     */
    public Cell titleCell(Row row, int column, String value, int fontSizeInPoints) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(titleStyle((short) fontSizeInPoints));
        return cell;
    }

    /**
     * Autosizes {@code columnCount} columns (0-based) on a streaming sheet.
     * SXSSF flushes rows out of memory, so autosize requires the columns to be
     * tracked <em>before</em> they are populated; callers that need autosize must
     * call {@link #trackColumnsForAutoSizing(SXSSFSheet)} (or POI's
     * {@code trackAllColumnsForAutoSizing()}) up front. This method tolerates an
     * untracked sheet by enabling all-column tracking defensively, then sizes.
     */
    public void autoSize(Sheet sheet, int columnCount) {
        if (sheet instanceof SXSSFSheet sxssf) {
            // Defensive: if the caller forgot to track up front, enable it now.
            // (Effective only for not-yet-flushed columns — see the javadoc.)
            sxssf.trackAllColumnsForAutoSizing();
        }
        for (int c = 0; c < columnCount; c++) {
            sheet.autoSizeColumn(c);
        }
    }

    /**
     * Enables autosize tracking for all columns of a streaming sheet. MUST be
     * called before writing rows for {@link #autoSize} to measure flushed rows
     * correctly (SXSSF constraint).
     */
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
