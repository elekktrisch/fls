package ch.alpenflight.flights.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface FlightReportRepository {

    List<UUID> PERSON_FILTER_CREW_TYPES = List.of(
            FlightCrewTypeIds.PILOT_OR_STUDENT,
            FlightCrewTypeIds.CO_PILOT,
            FlightCrewTypeIds.FLIGHT_INSTRUCTOR);

    record ReportRow(
            UUID flightId,
            short flightAircraftTypeLegacyId,
            @Nullable LocalDate flightDate,
            @Nullable Instant startDateTime,
            @Nullable Instant ldgDateTime,
            boolean noStartTimeInformation,
            boolean noLdgTimeInformation,
            @Nullable Instant flightPlanOpenedOn,
            UUID processStateId,
            boolean soloFlight,
            @Nullable String comment,
            @Nullable String startTypeCode,
            @Nullable String immatriculation,
            @Nullable String pilotName,
            @Nullable String secondCrewName,
            @Nullable String flightCode,
            @Nullable String flightTypeName,
            @Nullable String startLocation,
            @Nullable String ldgLocation,
            @Nullable UUID towedGliderFlightId,
            @Nullable UUID towFlightId,
            @Nullable Instant towStartDateTime,
            @Nullable Instant towLdgDateTime,
            boolean towNoStartTimeInformation,
            boolean towNoLdgTimeInformation,
            @Nullable Instant towFlightPlanOpenedOn,
            @Nullable UUID towProcessStateId,
            @Nullable String towImmatriculation,
            @Nullable String towPilotName,
            @Nullable String towFlightCode,
            @Nullable String towFlightTypeName,
            @Nullable String towStartLocation,
            @Nullable String towLdgLocation) {}

    List<ReportRow> findReportPage(ReportCriteria criteria,
                                   int offset,
                                   int limit,
                                   boolean sortBySeconds,
                                   boolean sortAsc);

    long countReport(ReportCriteria criteria);

    record SummaryRow(
            short aircraftType,
            boolean soloFlight,
            @Nullable Short nrOfLdgs,
            @Nullable Short nrOfLdgsOnStartLocation,
            boolean noStartTimeInformation,
            boolean noLdgTimeInformation,
            @Nullable UUID startLocationId,
            @Nullable UUID ldgLocationId,
            long durationSeconds,
            @Nullable String flightTypeName,
            boolean isPilotOrStudent,
            boolean isCoPilot,
            boolean isFlightInstructor) {}

    List<SummaryRow> findSummaryRows(ReportCriteria criteria);

    record ReportCriteria(UUID tenantId,
                          @Nullable LocalDate from,
                          @Nullable LocalDate to,
                          @Nullable UUID personId,
                          @Nullable UUID locationId,
                          boolean gliderFlights,
                          boolean motorFlights,
                          boolean towFlights) {}
}
