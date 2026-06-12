package ch.alpenflight.flights.web;

import ch.alpenflight.flights.application.FlightReportDtos.FlightReportDataRecord;
import ch.alpenflight.flights.application.FlightReportDtos.FlightReportResult;
import ch.alpenflight.flights.application.FlightReportDtos.TowFlightReportDataRecord;
import ch.alpenflight.flights.domain.FlightCategory;
import ch.alpenflight.platform.id.FlightId;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The single known dataset behind the FlightReports Excel parity check (J-7 T-08).
 * Both sides of the harness derive from this:
 *
 * <ul>
 *   <li>{@link FlightReportGoldenFixture} renders these rows BY HAND into the
 *       committed golden {@code .xlsx} (the contract);</li>
 *   <li>{@link #result()} feeds the SAME rows to the production
 *       {@link FlightReportExcelWriter} so the harness compares writer-output ↔
 *       golden fixture.</li>
 * </ul>
 *
 * <p>Values are chosen to exercise every parity-load-bearing format string and the
 * documented oracle quirks: HH:MM time, [H]:MM duration that exceeds 24h on the tow
 * column-set so {@code [H]} matters, IsSoloFlight 0/1, StartType as the legacy
 * {@code AircraftStartType} int ({@code WINCH=2}, {@code AEROTOW=1} on the tow row),
 * raw AirState/ProcessState ints, a plain glider row AND an aerotow glider carrying
 * the nested TowFlight block (cols 19-30).
 *
 * <p>Fixed UUIDs + a fixed generation timestamp make the fixture deterministic — the
 * generator emits byte-identical output across runs, so {@link FlightReportGoldenFixtureTest}
 * can diff the committed snapshot.
 */
final class FlightReportGoldenDataset {

    private FlightReportGoldenDataset() {}

    /** Fixed C3 "Excel Erstellt:" timestamp (UTC wall-clock) — deterministic fixture. */
    static final LocalDateTime GENERATED_AT_UTC = LocalDateTime.of(2026, 6, 9, 14, 30, 15);

    /** The generation instant the writer is handed (UTC wall-clock of {@link #GENERATED_AT_UTC}). */
    static final Instant GENERATED_AT = GENERATED_AT_UTC.toInstant(ZoneOffset.UTC);

    /** Header labels — the writer's exact legacy order (asserted equal via the harness). */
    static final String[] HEADERS = legacyHeaders();

    private static final UUID GLIDER_ID = UUID.fromString("019e30c5-0000-7001-8000-000000000001");
    private static final UUID AEROTOW_GLIDER_ID = UUID.fromString("019e30c5-0000-7001-8000-000000000002");
    private static final UUID TOW_ID = UUID.fromString("019e30c5-0000-7001-8000-000000000003");

    /** Plain glider: winch launch (StartType 2), dual crew, not solo. */
    private static GoldenRow plainGlider() {
        return new GoldenRow(
                GLIDER_ID.toString(),
                LocalDate.of(2026, 5, 15),
                "HB-3210",
                "Tester Anna",
                "Copilot Beat",
                1,    // airState
                2,    // processState
                "SCH",
                "Schul",
                LocalDateTime.of(2026, 5, 15, 8, 5),   // start 08:05 UTC
                LocalDateTime.of(2026, 5, 15, 9, 35),  // ldg 09:35 UTC
                90 * 60,    // 1:30 duration
                false,      // not solo
                2,          // StartType WINCH=2 (legacy AircraftStartType.WinchLaunch)
                "Birrfeld",
                "Birrfeld",
                "Schulflug Doppelsitzer",
                null);
    }

    /** Aerotow glider (StartType 1) carrying the nested tow block; tow duration > 24h to exercise [H]. */
    private static GoldenRow aerotowGlider() {
        GoldenTow tow = new GoldenTow(
                TOW_ID.toString(),
                "HB-TOW",
                "Towpilot Carl",
                LocalDateTime.of(2026, 5, 15, 10, 0),
                LocalDateTime.of(2026, 5, 16, 11, 0),   // 25h span → [H]:MM must show 25:00
                25 * 3600,
                "Birrfeld",
                "Birrfeld",
                "SLEP",
                "Schlepp",
                1,    // airState
                2);   // processState
        return new GoldenRow(
                AEROTOW_GLIDER_ID.toString(),
                LocalDate.of(2026, 5, 15),
                "HB-1801",
                "Tester Anna",
                null,       // solo, no second crew
                1,
                2,
                "STR",
                "Streckenflug",
                LocalDateTime.of(2026, 5, 15, 10, 0),
                LocalDateTime.of(2026, 5, 15, 13, 45),  // 3:45
                225 * 60,
                true,       // solo
                1,          // StartType AEROTOW=1 (legacy AircraftStartType.Towing)
                "Birrfeld",
                "Schänis",
                "Soloflug mit Schlepp",
                tow);
    }

    static List<GoldenRow> rows() {
        return List.of(plainGlider(), aerotowGlider());
    }

    /** The same rows as a {@link FlightReportResult} for the production writer. */
    static FlightReportResult result() {
        return new FlightReportResult(
                List.of(toRecord(plainGlider()), toRecord(aerotowGlider())),
                rows().size(),
                List.of());
    }

    private static FlightReportDataRecord toRecord(GoldenRow gr) {
        TowFlightReportDataRecord tow = gr.tow() == null ? null : toTowRecord(gr.tow());
        return new FlightReportDataRecord(
                FlightId.of(UUID.fromString(gr.flightId())),
                gr.flightDate(),
                gr.immatriculation(),
                gr.pilotName(),
                gr.secondCrewName(),
                gr.flightComment(),
                gr.airState(),
                gr.processState(),
                gr.flightCode(),
                gr.flightTypeName(),
                gr.startTime().toInstant(ZoneOffset.UTC),
                gr.ldgTime().toInstant(ZoneOffset.UTC),
                Duration.ofSeconds(gr.durationSeconds()),
                gr.isSoloFlight(),
                gr.startType(),
                gr.startLocation(),
                gr.ldgLocation(),
                FlightCategory.GLIDER,
                tow == null ? null : FlightId.of(UUID.fromString(gr.tow().towFlightId())),
                tow);
    }

    private static TowFlightReportDataRecord toTowRecord(GoldenTow t) {
        return new TowFlightReportDataRecord(
                FlightId.of(UUID.fromString(t.towFlightId())),
                t.immatriculation(),
                t.pilotName(),
                t.flightCode(),
                t.flightTypeName(),
                t.startTime().toInstant(ZoneOffset.UTC),
                t.ldgTime().toInstant(ZoneOffset.UTC),
                t.startLocation(),
                t.ldgLocation(),
                Duration.ofSeconds(t.durationSeconds()),
                t.airState(),
                t.processState());
    }

    private static String[] legacyHeaders() {
        return new String[] {
            "Flight ID", "FlightDate", "Immatriculation", "PilotName", "SecondCrewName",
            "AirState", "ProcessState", "FlightCode", "FlightTypeName", "StartTime UTC",
            "LdgTime UCT", "FlightDuration", "IsSoloFlight", "StartType", "StartLocation",
            "LdgLocation", "", "FlightComment", "TowFlight-FlightId", "TowFlight-Immatriculation",
            "TowFlight-PilotName", "TowFlight-StartTime UTC", "TowFlight-LdgTime UTC",
            "TowFlight-FlightDuration", "TowFlight-StartLocation", "TowFlight-LdgLocation",
            "TowFlight-FlightCode", "TowFlight-FlightTypeName", "TowFlight-AirState",
            "TowFlight-ProcessState",
        };
    }

    /** One report row, value-only (no POI). */
    record GoldenRow(
            String flightId,
            LocalDate flightDate,
            String immatriculation,
            String pilotName,
            @Nullable String secondCrewName,
            int airState,
            int processState,
            String flightCode,
            String flightTypeName,
            LocalDateTime startTime,
            LocalDateTime ldgTime,
            long durationSeconds,
            boolean isSoloFlight,
            int startType,
            String startLocation,
            String ldgLocation,
            String flightComment,
            @Nullable GoldenTow tow) {}

    /** The nested tow block, value-only. */
    record GoldenTow(
            String towFlightId,
            String immatriculation,
            String pilotName,
            LocalDateTime startTime,
            LocalDateTime ldgTime,
            long durationSeconds,
            String startLocation,
            String ldgLocation,
            String flightCode,
            String flightTypeName,
            int airState,
            int processState) {}
}
