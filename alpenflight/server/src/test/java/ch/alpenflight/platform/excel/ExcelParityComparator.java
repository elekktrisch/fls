package ch.alpenflight.platform.excel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public final class ExcelParityComparator {

    private static final double NUMERIC_EPSILON = 1e-9;

    private static final String UNSTYLED_DEFAULT_NUMBER_FORMAT = "General";

    private ExcelParityComparator() {}

    public static Diff compareFiles(Path expected, Path actual) throws IOException {
        try (InputStream eIn = Files.newInputStream(expected);
                InputStream aIn = Files.newInputStream(actual);
                Workbook eWb = new XSSFWorkbook(eIn);
                Workbook aWb = new XSSFWorkbook(aIn)) {
            return compare(eWb, aWb);
        }
    }

    public static Diff compare(Path expected, byte[] actualBytes) throws IOException {
        try (InputStream eIn = Files.newInputStream(expected);
                Workbook eWb = new XSSFWorkbook(eIn);
                Workbook aWb = new XSSFWorkbook(new java.io.ByteArrayInputStream(actualBytes))) {
            return compare(eWb, aWb);
        }
    }

    public static Diff compare(Workbook expected, Workbook actual) {
        List<String> mismatches = new ArrayList<>();

        if (expected.getNumberOfSheets() != actual.getNumberOfSheets()) {
            mismatches.add("WORKBOOK: sheet count expected="
                    + expected.getNumberOfSheets() + " actual=" + actual.getNumberOfSheets());
        }

        int sheets = Math.max(expected.getNumberOfSheets(), actual.getNumberOfSheets());
        for (int s = 0; s < sheets; s++) {
            Sheet eSheet = s < expected.getNumberOfSheets() ? expected.getSheetAt(s) : null;
            Sheet aSheet = s < actual.getNumberOfSheets() ? actual.getSheetAt(s) : null;
            if (eSheet == null || aSheet == null) {
                mismatches.add("WORKBOOK: sheet index " + s + " present in only one workbook ("
                        + name(eSheet) + " vs " + name(aSheet) + ")");
                continue;
            }
            if (!eSheet.getSheetName().equals(aSheet.getSheetName())) {
                mismatches.add("SHEET[" + s + "]: name expected='" + eSheet.getSheetName()
                        + "' actual='" + aSheet.getSheetName() + "'");
            }
            compareSheet(eSheet, aSheet, mismatches);
        }
        return new Diff(List.copyOf(mismatches));
    }

    private static void compareSheet(Sheet expected, Sheet actual, List<String> mismatches) {
        String sheetName = expected.getSheetName();
        int firstRow = Math.min(safeFirst(expected), safeFirst(actual));
        int lastRow = Math.max(expected.getLastRowNum(), actual.getLastRowNum());
        for (int r = firstRow; r <= lastRow; r++) {
            Row eRow = expected.getRow(r);
            Row aRow = actual.getRow(r);
            int lastCol = Math.max(
                    eRow == null ? -1 : eRow.getLastCellNum() - 1,
                    aRow == null ? -1 : aRow.getLastCellNum() - 1);
            for (int c = 0; c <= lastCol; c++) {
                Cell eCell = eRow == null ? null : eRow.getCell(c);
                Cell aCell = aRow == null ? null : aRow.getCell(c);
                compareCell(sheetName, r, c, eCell, aCell, mismatches);
            }
        }
    }

    private static void compareCell(
            String sheet, int r, int c, Cell expected, Cell actual, List<String> mismatches) {
        boolean ePopulated = isPopulated(expected);
        boolean aPopulated = isPopulated(actual);
        if (!ePopulated && !aPopulated) {
            return;
        }
        String addr = sheet + "!" + new CellReference(r, c).formatAsString();
        if (ePopulated != aPopulated) {
            mismatches.add(addr + ": cell present in only one workbook"
                    + " (expected=" + render(expected) + " actual=" + render(actual) + ")");
            return;
        }

        CellType eType = expected.getCellType();
        CellType aType = actual.getCellType();
        if (eType != aType) {
            mismatches.add(addr + ": type expected=" + eType + " actual=" + aType);
            return;
        }

        switch (eType) {
            case STRING -> {
                if (!expected.getStringCellValue().equals(actual.getStringCellValue())) {
                    mismatches.add(addr + ": string value expected='" + expected.getStringCellValue()
                            + "' actual='" + actual.getStringCellValue() + "'");
                }
            }
            case NUMERIC -> {
                double ev = expected.getNumericCellValue();
                double av = actual.getNumericCellValue();
                if (Math.abs(ev - av) > NUMERIC_EPSILON) {
                    mismatches.add(addr + ": numeric value expected=" + ev + " actual=" + av);
                }
            }
            case BOOLEAN -> {
                if (expected.getBooleanCellValue() != actual.getBooleanCellValue()) {
                    mismatches.add(addr + ": boolean value expected=" + expected.getBooleanCellValue()
                            + " actual=" + actual.getBooleanCellValue());
                }
            }
            case FORMULA -> {
                if (!expected.getCellFormula().equals(actual.getCellFormula())) {
                    mismatches.add(addr + ": formula expected='" + expected.getCellFormula()
                            + "' actual='" + actual.getCellFormula() + "'");
                }
            }
            default -> { }
        }

        String expectedNumberFormat = formatString(expected);
        String actualNumberFormat = formatString(actual);
        if (!expectedNumberFormat.equals(actualNumberFormat)) {
            mismatches.add(addr + ": number-format expected='" + expectedNumberFormat
                    + "' actual='" + actualNumberFormat + "'");
        }
    }

    private static String formatString(Cell cell) {
        String fmt = cell.getCellStyle().getDataFormatString();
        return fmt == null ? UNSTYLED_DEFAULT_NUMBER_FORMAT : fmt;
    }

    private static boolean isPopulated(Cell cell) {
        if (cell == null) {
            return false;
        }
        if (cell.getCellType() == CellType.BLANK) {
            return !UNSTYLED_DEFAULT_NUMBER_FORMAT.equals(formatString(cell));
        }
        if (cell.getCellType() == CellType.STRING) {
            return !cell.getStringCellValue().isEmpty();
        }
        return true;
    }

    private static int safeFirst(Sheet sheet) {
        return sheet.getFirstRowNum() < 0 ? 0 : sheet.getFirstRowNum();
    }

    private static String name(Sheet sheet) {
        return sheet == null ? "<absent>" : sheet.getSheetName();
    }

    private static String render(Cell cell) {
        if (!isPopulated(cell)) {
            return "<empty>";
        }
        return switch (cell.getCellType()) {
            case STRING -> "'" + cell.getStringCellValue() + "'";
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? "date(" + cell.getNumericCellValue() + ")"
                    : Double.toString(cell.getNumericCellValue());
            case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
            case FORMULA -> "=" + cell.getCellFormula();
            default -> "<" + cell.getCellType() + ">";
        };
    }

    public record Diff(List<String> mismatches) {

        public boolean isEqual() {
            return mismatches.isEmpty();
        }

        public String describe() {
            if (mismatches.isEmpty()) {
                return "Workbooks are cell-parity-equal (values, types, number-formats).";
            }
            StringBuilder sb = new StringBuilder(
                    mismatches.size() + " cell parity mismatch(es):\n");
            for (String m : mismatches) {
                sb.append("  - ").append(m).append('\n');
            }
            return sb.toString();
        }
    }
}
