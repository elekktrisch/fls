package ch.alpenflight.flights.web;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One-shot generator for the committed FlightReports golden fixture (J-7 T-08).
 * Writes {@link FlightReportGoldenFixture}'s output to the test-resources path so the
 * deterministic fixture can be regenerated + re-committed when the documented contract
 * legitimately changes (or when a live-legacy fixture swaps in via the fan-out gate).
 *
 * <p>NOT a test — run manually:
 * <pre>{@code
 *   ./gradlew :alpenflight-server:testClasses
 *   java -cp <test-runtime-classpath> \
 *     ch.alpenflight.flights.web.FlightReportGoldenFixtureGenerator \
 *     src/test/resources/excel-parity/flight-reports-legacy-golden.xlsx
 * }</pre>
 * The committed bytes are then guarded by {@link FlightReportGoldenFixtureTest}.
 */
public final class FlightReportGoldenFixtureGenerator {

    private FlightReportGoldenFixtureGenerator() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException(
                    "usage: FlightReportGoldenFixtureGenerator <output-xlsx-path>");
        }
        Path target = Path.of(args[0]);
        Files.createDirectories(target.toAbsolutePath().getParent());
        try (OutputStream out = Files.newOutputStream(target)) {
            FlightReportGoldenFixture.write(out);
        }
        System.out.println("Wrote golden fixture: " + target.toAbsolutePath());
    }
}
