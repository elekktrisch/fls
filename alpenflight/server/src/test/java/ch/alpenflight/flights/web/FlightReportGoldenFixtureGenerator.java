package ch.alpenflight.flights.web;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

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
