package ch.alpenflight.flights.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import ch.alpenflight.platform.excel.ExcelParityComparator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

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
