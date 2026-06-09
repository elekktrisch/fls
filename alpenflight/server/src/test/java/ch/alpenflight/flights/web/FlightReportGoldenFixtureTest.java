package ch.alpenflight.flights.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import ch.alpenflight.platform.excel.ExcelParityComparator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/**
 * Guards the committed golden fixture (J-7 T-08, story S-096): the checked-in
 * {@code excel-parity/flight-reports-legacy-golden.xlsx} must stay cell-parity-equal
 * to what {@link FlightReportGoldenFixture} generates. This stops the fixture from
 * drifting away from the documented S-093/oracle contract unnoticed — if the
 * generator (the contract-in-code) and the committed bytes diverge, this fails with
 * the exact mismatching cells.
 *
 * <p>Plain JUnit/AssertJ (no Postgres/Spring) — runs in {@code check} via the
 * standard {@code test} task.
 */
class FlightReportGoldenFixtureTest {

    @Test
    void committedGoldenFixture_matchesTheContractGenerator() throws IOException {
        byte[] regenerated = generate();

        try (InputStream committed =
                FlightReportGoldenFixtureTest.class.getResourceAsStream(
                        FlightReportGoldenFixture.RESOURCE_PATH)) {
            if (committed == null) {
                fail("Committed golden fixture missing on the classpath: "
                        + FlightReportGoldenFixture.RESOURCE_PATH
                        + " — generate it once with FlightReportGoldenFixtureGenerator and commit it under"
                        + " src/test/resources/excel-parity/.");
                return;
            }
            byte[] committedBytes = committed.readAllBytes();

            try (var expected = new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                            new java.io.ByteArrayInputStream(committedBytes));
                    var actual = new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                            new java.io.ByteArrayInputStream(regenerated))) {
                ExcelParityComparator.Diff diff = ExcelParityComparator.compare(expected, actual);
                assertThat(diff.isEqual())
                        .as("committed golden fixture vs. contract generator:\n%s", diff.describe())
                        .isTrue();
            }
        }
    }

    private static byte[] generate() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FlightReportGoldenFixture.write(out);
        return out.toByteArray();
    }
}
