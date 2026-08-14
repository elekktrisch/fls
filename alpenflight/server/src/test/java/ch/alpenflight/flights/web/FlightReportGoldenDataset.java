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

final class FlightReportGoldenDataset {

    private FlightReportGoldenDataset() {}

    static final LocalDateTime GENERATED_AT_UTC = LocalDateTime.of(2026, 6, 9, 14, 30, 15);

    static final Instant GENERATED_AT = GENERATED_AT_UTC.toInstant(ZoneOffset.UTC);

    static final String[] HEADERS = legacyHeaders();

    private static final UUID GLIDER_ID = UUID.fromString("019e30c5-0000-7001-8000-000000000001");
    private static final UUID AEROTOW_GLIDER_ID = UUID.fromString("019e30c5-0000-7001-8000-000000000002");
    private static final UUID TOW_ID = UUID.fromString("019e30c5-0000-7001-8000-000000000003");

    private static GoldenRow plainGlider() {
        return new GoldenRow(
                GLIDER_ID.toString(),
                LocalDate.of(2026, 5, 15),
                "HB-3210",
                "Tester Anna",
                "Copilot Beat",
                1,
                2,
                "SCH",
                "Schul",
                LocalDateTime.of(2026, 5, 15, 8, 5),
                LocalDateTime.of(2026, 5, 15, 9, 35),
                90 * 60,
                false,
                2,
                "Birrfeld",
                "Birrfeld",
                "Schulflug Doppelsitzer",
                null);
    }

    private static GoldenRow aerotowGlider() {
        GoldenTow tow = new GoldenTow(
                TOW_ID.toString(),
                "HB-TOW",
                "Towpilot Carl",
                LocalDateTime.of(2026, 5, 15, 10, 0),
                LocalDateTime.of(2026, 5, 16, 11, 0),
                25 * 3600,
                "Birrfeld",
                "Birrfeld",
                "SLEP",
                "Schlepp",
                1,
                2);
        return new GoldenRow(
                AEROTOW_GLIDER_ID.toString(),
                LocalDate.of(2026, 5, 15),
                "HB-1801",
                "Tester Anna",
                null,
                1,
                2,
                "STR",
                "Streckenflug",
                LocalDateTime.of(2026, 5, 15, 10, 0),
                LocalDateTime.of(2026, 5, 15, 13, 45),
                225 * 60,
                true,
                1,
                "Birrfeld",
                "Schänis",
                "Soloflug mit Schlepp",
                tow);
    }

    static List<GoldenRow> rows() {
        return List.of(plainGlider(), aerotowGlider());
    }

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
