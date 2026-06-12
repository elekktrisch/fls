package ch.alpenflight.flights.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import ch.alpenflight.platform.excel.ExcelParityComparator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/**
 * The S-096 Excel parity harness, FlightReports-scoped (J-7 T-08). Runs the
 * production {@link FlightReportExcelWriter} over the known
 * {@link FlightReportGoldenDataset} and asserts its output is <b>cell-parity-equal</b>
 * to the committed golden fixture — values + types + number-format strings, tolerant
 * of cosmetic font/width/bold/fill differences ({@link ExcelParityComparator}).
 *
 * <p><b>Scope.</b> FlightReports export ONLY. {@code DeliveryMailExport} +
 * {@code AircraftStatisticReport} are explicitly OUT of scope here — they ride J-10,
 * which adds their fixtures and reuses this same comparator (it is NOT rebuilt). See
 * {@code src/test/resources/excel-parity/README.md}.
 *
 * <p><b>Fixture provenance.</b> The golden fixture is derived from the S-093 inventory
 * + the legacy behavior oracle, NOT a live legacy export (the Mono/MSSQL legacy stack
 * is unrunnable on this Alpine/musl box). This harness proves our writer matches the
 * DOCUMENTED contract; the live-legacy byte-match is a fanout-gate concern. Full
 * provenance: {@link FlightReportGoldenFixture}.
 *
 * <p>Despite the {@code IT} suffix this needs no Postgres/Spring — it drives the writer
 * directly. It runs in {@code check} via the standard {@code test} task.
 */
class FlightReportExcelParityIT {

    @Test
    void writerOutput_isCellParityEqualToTheGoldenFixture() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FlightReportExcelWriter.write(
                FlightReportGoldenDataset.result(), FlightReportGoldenDataset.GENERATED_AT, out);
        byte[] writerBytes = out.toByteArray();

        try (InputStream committed =
                FlightReportExcelParityIT.class.getResourceAsStream(
                        FlightReportGoldenFixture.RESOURCE_PATH)) {
            if (committed == null) {
                fail("Committed golden fixture missing: " + FlightReportGoldenFixture.RESOURCE_PATH);
                return;
            }
            try (var expected = new org.apache.poi.xssf.usermodel.XSSFWorkbook(committed);
                    var actual = new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                            new java.io.ByteArrayInputStream(writerBytes))) {
                ExcelParityComparator.Diff diff = ExcelParityComparator.compare(expected, actual);
                assertThat(diff.isEqual())
                        .as("FlightReportExcelWriter output vs. golden fixture:\n%s", diff.describe())
                        .isTrue();
            }
        }
    }
}
