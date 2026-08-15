package ch.alpenflight.flights.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FlightReportRowRepository {

    FlightReportRow save(FlightReportRow row);

    Optional<FlightReportRow> findByFlightId(UUID flightId);

    void delete(FlightReportRow row);

    List<UUID> findFlightIdsByTowFlightId(UUID towFlightId);

    List<UUID> findFlightIdsByTowedGliderFlightId(UUID towedGliderFlightId);

    List<UUID> findAllFlightIds();

    List<UUID> findFlightIdsByCrewPersonId(UUID personId);
}
